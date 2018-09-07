/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.topology;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ClusterNotFoundException;
import org.apache.ambari.server.DuplicateResourceException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.StackAccessException;
import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.HostRoleCommandFactory;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.stomp.HostLevelParamsHolder;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.ClusterRequest;
import org.apache.ambari.server.controller.ConfigGroupRequest;
import org.apache.ambari.server.controller.RequestStatusResponse;
import org.apache.ambari.server.controller.RootComponent;
import org.apache.ambari.server.controller.ServiceComponentHostRequest;
import org.apache.ambari.server.controller.ServiceComponentRequest;
import org.apache.ambari.server.controller.ServiceGroupRequest;
import org.apache.ambari.server.controller.ServiceGroupResponse;
import org.apache.ambari.server.controller.ServiceRequest;
import org.apache.ambari.server.controller.ServiceResponse;
import org.apache.ambari.server.controller.internal.ComponentResourceProvider;
import org.apache.ambari.server.controller.internal.ConfigGroupResourceProvider;
import org.apache.ambari.server.controller.internal.ExportBlueprintRequest;
import org.apache.ambari.server.controller.internal.HostComponentResourceProvider;
import org.apache.ambari.server.controller.internal.HostResourceProvider;
import org.apache.ambari.server.controller.internal.RequestImpl;
import org.apache.ambari.server.controller.internal.ServiceDependencyResourceProvider;
import org.apache.ambari.server.controller.internal.ServiceGroupDependencyResourceProvider;
import org.apache.ambari.server.controller.internal.ServiceGroupResourceProvider;
import org.apache.ambari.server.controller.internal.ServiceResourceProvider;
import org.apache.ambari.server.controller.internal.Stack;
import org.apache.ambari.server.controller.internal.StackDefinition;
import org.apache.ambari.server.controller.predicate.EqualsPredicate;
import org.apache.ambari.server.controller.spi.ClusterController;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceProvider;
import org.apache.ambari.server.controller.utilities.ClusterControllerHelper;
import org.apache.ambari.server.security.authorization.AuthorizationException;
import org.apache.ambari.server.stack.NoSuchStackException;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigFactory;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.DesiredConfig;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.SecurityType;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.state.configgroup.ConfigGroup;
import org.apache.ambari.server.utils.RetryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Striped;
import com.google.inject.Provider;


/**
 * Provides topology related information as well as access to the core Ambari functionality.
 */
public class AmbariContext {

  public enum TaskType {INSTALL, START}

  @Inject
  private PersistedState persistedState;

  /**
   * Used for creating read-only instances of existing {@link Config} in order
   * to send them to the {@link ConfigGroupResourceProvider} to create
   * {@link ConfigGroup}s.
   */
  @Inject
  ConfigFactory configFactory;

  @Inject
  StackFactory stackFactory;

  /**
   * Used for getting configuration property values from stack and services.
   */
  @Inject
  private Provider<ConfigHelper> configHelper;

  @Inject
  private ComponentResolver resolver;

  @Inject
  HostLevelParamsHolder hostLevelParamsHolder;

  @Inject
  private SecurityConfigurationFactory securityConfigurationFactory;

  @Inject
  private Provider<AmbariMetaInfo> ambariMetaInfo;

  @Inject
  private Provider<AmbariManagementController> controller;

  //todo: task id's.  Use existing mechanism for getting next task id sequence
  private final static AtomicLong nextTaskId = new AtomicLong(10000);
  static final String DEFAULT_SERVICE_GROUP_NAME = "default_service_group"; // exposed for test

  private static ClusterController clusterController;
  private static HostRoleCommandFactory hostRoleCommandFactory;
  private static HostResourceProvider hostResourceProvider;
  private static ServiceGroupResourceProvider serviceGroupResourceProvider;
  private static ServiceDependencyResourceProvider serviceDependencyResourceProvider;
  private static ServiceGroupDependencyResourceProvider serviceGroupDependencyResourceProvider;
  private static ServiceResourceProvider serviceResourceProvider;
  private static ComponentResourceProvider componentResourceProvider;
  private static HostComponentResourceProvider hostComponentResourceProvider;

  private final static Logger LOG = LoggerFactory.getLogger(AmbariContext.class);


  /**
   * When config groups are created using Blueprints these are created when
   * hosts join a hostgroup and are added to the corresponding config group.
   * Since hosts join in parallel there might be a race condition in creating
   * the config group a host is to be added to. Thus we need to synchronize
   * the creation of config groups with the same name.
   */
  private Striped<Lock> configGroupCreateLock = Striped.lazyWeakLock(1);

  public boolean isClusterKerberosEnabled(long clusterId) {
    Cluster cluster;
    try {
      cluster = getController().getClusters().getClusterById(clusterId);
    } catch (AmbariException e) {
      throw new RuntimeException("Parent Cluster resource doesn't exist.  clusterId= " + clusterId);
    }
    return cluster.getSecurityType() == SecurityType.KERBEROS;
  }

  //todo: change return type to a topology abstraction
  public HostRoleCommand createAmbariTask(long requestId, long stageId, String component, String host,
                                          TaskType type, boolean skipFailure) {
    HostRoleCommand task = hostRoleCommandFactory.create(
            host, Role.valueOf(component), null, RoleCommand.valueOf(type.name()), false, skipFailure);
    task.setStatus(HostRoleStatus.PENDING);
    task.setCommandDetail(String.format("Logical Task: %s component %s on host %s", type.name(), component, host));
    task.setTaskId(nextTaskId.getAndIncrement());
    task.setRequestId(requestId);
    task.setStageId(stageId);

    return task;
  }

  //todo: change return type to a topology abstraction
  public HostRoleCommand createAmbariTask(long taskId, long requestId, long stageId,
                                          String component, String host, TaskType type, boolean skipFailure) {
    synchronized (nextTaskId) {
      if (nextTaskId.get() <= taskId) {
        nextTaskId.set(taskId + 1);
      }
    }

    HostRoleCommand task = hostRoleCommandFactory.create(
        host, Role.valueOf(component), null, RoleCommand.valueOf(type.name()), false, skipFailure);
    task.setStatus(HostRoleStatus.PENDING);
    task.setCommandDetail(String.format("Logical Task: %s component %s on host %s",
        type.name(), component, host));
    task.setTaskId(taskId);
    task.setRequestId(requestId);
    task.setStageId(stageId);

    return task;
  }

  public HostRoleCommand getPhysicalTask(long id) {
    return getController().getActionManager().getTaskById(id);
  }

  public Collection<HostRoleCommand> getPhysicalTasks(Collection<Long> ids) {
    return getController().getActionManager().getTasks(ids);
  }

  public void createAmbariResources(ClusterTopology topology, String clusterName, SecurityType securityType) {
    Set<StackId> stackIds = topology.getStackIds();
    createAmbariClusterResource(clusterName, stackIds, securityType, topology.getSetting().getClusterSettings());
    createAmbariServiceAndComponentResources(topology, clusterName);
  }

  private void createAmbariClusterResource(String clusterName, Set<StackId> stackIds, SecurityType securityType,
                                           Map<String, String> clusterSettings) {
    String stackInfo = stackIds.iterator().next().toString(); // temporary
    final ClusterRequest clusterRequest = new ClusterRequest(null, clusterName, null, securityType, stackInfo, null);
    try {
      RetryHelper.executeWithRetry(() -> {
        getController().createCluster(clusterRequest);
        return null;
      });

      addClusterSettings(clusterName, clusterSettings);
    } catch (AmbariException e) {
      LOG.error("Failed to create Cluster resource: ", e);
      if (e.getCause() instanceof DuplicateResourceException) {
        throw new IllegalArgumentException(e);
      } else {
        throw new RuntimeException("Failed to create Cluster resource: " + e, e);
      }
    }
  }

  // FIXME temporarily add default cluster settings -- should be provided by ClusterImpl itself
  private void addClusterSettings(String clusterName, Map<String, String> clusterSettings) throws AmbariException {
    Cluster cluster = getController().getClusters().getCluster(clusterName);
    Map<String, String> defaultClusterSettings =
      getController().getAmbariMetaInfo().getClusterProperties().stream()
        .collect(toMap(PropertyInfo::getName, PropertyInfo::getValue));

    // Override default settings with those coming from blueprint / cluster template
    defaultClusterSettings.putAll(clusterSettings);

    for (Map.Entry<String, String> setting : defaultClusterSettings.entrySet()) {
      cluster.addClusterSetting(setting.getKey(), setting.getValue());
    }
  }

  private void createAmbariServiceAndComponentResources(ClusterTopology topology, String clusterName) {
    Set<ServiceGroupRequest> serviceGroupRequests = topology.getComponents()
      .map(c -> new ServiceGroupRequest(clusterName, c.effectiveServiceGroupName(), c.stackId().getStackId()))
      .collect(toSet());

    Set<ServiceRequest> serviceRequests = topology.getComponents()
      .map(c -> new ServiceRequest(
          clusterName, c.effectiveServiceGroupName(), c.effectiveServiceName(), c.serviceType(), null,
            topology.getSetting().getCredentialStoreEnabled(c.effectiveServiceName())
        ))
      .collect(toSet());

    Set<ServiceComponentRequest> componentRequests = topology.getComponents()
      .map( c ->
            new ServiceComponentRequest(
              clusterName,
              c.effectiveServiceGroupName(),
              c.effectiveServiceName(),
              c.componentName(),
              c.componentName(),
              State.INIT.name(),
              topology.getSetting().getRecoveryEnabled(c.effectiveServiceName(), c.componentName()))
      ) // FIXME settings by service type or name?
      .collect(toSet());

    try {
      if (!serviceGroupRequests.isEmpty()) {
        getServiceGroupResourceProvider().createServiceGroups(serviceGroupRequests);
      }
      getServiceResourceProvider().createServices(serviceRequests);
      getComponentResourceProvider().createComponents(componentRequests);
    } catch (AmbariException | AuthorizationException e) {
      throw new RuntimeException("Failed to persist service and component resources: " + e, e);
    }

    // set all services state to INSTALLED->STARTED
    // this is required so the user can start failed services at the service level
    Map<String, Object> installProps = new HashMap<>();
    installProps.put(ServiceResourceProvider.SERVICE_SERVICE_STATE_PROPERTY_ID, "INSTALLED");
    installProps.put(ServiceResourceProvider.SERVICE_CLUSTER_NAME_PROPERTY_ID, clusterName);
    Map<String, Object> startProps = new HashMap<>();
    startProps.put(ServiceResourceProvider.SERVICE_SERVICE_STATE_PROPERTY_ID, "STARTED");
    startProps.put(ServiceResourceProvider.SERVICE_CLUSTER_NAME_PROPERTY_ID, clusterName);
    Predicate predicate = new EqualsPredicate<>(
      ServiceResourceProvider.SERVICE_CLUSTER_NAME_PROPERTY_ID, clusterName);
    try {
      getServiceResourceProvider().updateResources(
          new RequestImpl(null, Collections.singleton(installProps), null, null), predicate);

      getServiceResourceProvider().updateResources(
        new RequestImpl(null, Collections.singleton(startProps), null, null), predicate);
    } catch (Exception e) {
      // just log as this won't prevent cluster from being provisioned correctly
      LOG.error("Unable to update state of services during cluster provision: " + e, e);
    }
  }

  public Set<ServiceResponse> getServices(String clusterName) {
    try {
      ServiceGroupRequest serviceGroupRequest = new ServiceGroupRequest(clusterName, null, null);
      Set<ServiceGroupResponse> serviceGroups = getServiceGroupResourceProvider().getServiceGroups(ImmutableSet.of(serviceGroupRequest));
      Set<ServiceRequest> serviceRequests = serviceGroups.stream().
        map(sg -> new ServiceRequest(clusterName, sg.getServiceGroupName(), null, null, null, null)).
        collect(toSet());
      return getServiceResourceProvider().getServices(serviceRequests);
    }
    catch (AmbariException ex) {
      throw new RuntimeException("Failed to load service groups and services", ex);
    }
  }

  public void createAmbariHostResources(long  clusterId, String hostName, Stream<ResolvedComponent> components)  {
    Host host;
    try {
      host = getController().getClusters().getHost(hostName);
    } catch (AmbariException e) {
      // system exception, shouldn't occur
      throw new RuntimeException(String.format(
          "Unable to obtain host instance '%s' when persisting host resources", hostName));
    }

    final Cluster cluster;
    try {
      cluster = getController().getClusters().getClusterById(clusterId);
    } catch (AmbariException e) {
      LOG.error("Cannot get cluster for clusterId = " + clusterId, e);
      throw new RuntimeException(e);
    }
    String clusterName = cluster.getClusterName();

    Map<String, Object> properties = new HashMap<>();
    properties.put(HostResourceProvider.HOST_CLUSTER_NAME_PROPERTY_ID, clusterName);
    properties.put(HostResourceProvider.HOST_HOST_NAME_PROPERTY_ID, hostName);
    properties.put(HostResourceProvider.HOST_RACK_INFO_PROPERTY_ID, host.getRackInfo());

    try {
      getHostResourceProvider().createHosts(new RequestImpl(null, Collections.singleton(properties), null, null));
    } catch (AmbariException | AuthorizationException e) {
      LOG.error("Unable to create host component resource for host {}", hostName, e);
      throw new RuntimeException(String.format("Unable to create host resource for host '%s': %s",
          hostName, e.toString()), e);
    }

    final Set<ServiceComponentHostRequest> requests = components
      .filter(component -> !component.componentName().equals(RootComponent.AMBARI_SERVER.name()))
      .map(component -> new ServiceComponentHostRequest(clusterName, component.effectiveServiceGroupName(), component.effectiveServiceName(), component.componentName(), component.componentName(),hostName, null))
      .collect(toSet());

    try {
      RetryHelper.executeWithRetry(() -> {
        getController().createHostComponents(requests, true);
        return null;
      });
      hostLevelParamsHolder.updateData(hostLevelParamsHolder.getCurrentData(host.getHostId()));
    } catch (AmbariException e) {
      LOG.error("Unable to create host component resource for host {}", hostName, e);
      throw new RuntimeException(String.format("Unable to create host component resource for host '%s': %s",
          hostName, e.toString()), e);
    }
  }

  public Long getNextRequestId() {
    return getController().getActionManager().getNextRequestId();
  }

  public AmbariManagementController getController() {
    return controller.get();
  }

  public static ClusterController getClusterController() {
    if (clusterController == null) {
      clusterController = ClusterControllerHelper.getClusterController();
    }
    return clusterController;
  }

  public static void init(HostRoleCommandFactory factory) {
    hostRoleCommandFactory = factory;
  }

  public ClusterTopology createClusterTopology(ExportBlueprintRequest request) throws InvalidTopologyException {
    StackDefinition stack = composeStacks(request.getStackIds());
    Blueprint bp = request.getBlueprint();
    return new ClusterTopologyImpl(this, request, stack);
  }

  public ClusterTopology createClusterTopology(ProvisionRequest request) throws InvalidTopologyException {
    BlueprintBasedClusterProvisionRequest provisionRequest = new BlueprintBasedClusterProvisionRequest(this, securityConfigurationFactory, request.getBlueprint(), request);
    Map<String, Set<ResolvedComponent>> resolved = resolver.resolveComponents(provisionRequest);
    return new ClusterTopologyImpl(this, provisionRequest, resolved);
  }

  public void downloadMissingMpacks(Set<MpackInstance> mpacks) {
      ResourceProvider resourceProvider = getClusterController().ensureResourceProvider(Resource.Type.Mpack);
    new DownloadMpacksTask(resourceProvider, ambariMetaInfo.get()).downloadMissingMpacks(mpacks);
  }

  public void registerHostWithConfigGroup(final String hostName, final ClusterTopology topology, final String groupName) {
    String qualifiedGroupName = getConfigurationGroupName(topology.getBlueprintName(), groupName);

    Lock configGroupLock = configGroupCreateLock.get(qualifiedGroupName);

    try {
      configGroupLock.lock();

      boolean hostAdded = RetryHelper.executeWithRetry(new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          return addHostToExistingConfigGroups(hostName, topology, qualifiedGroupName);
        }
      });
      if (!hostAdded) {
        createConfigGroupsAndRegisterHost(topology, groupName);
      }
    } catch (Exception e) {
      LOG.error("Unable to register config group for host: ", e);
      throw new RuntimeException("Unable to register config group for host: " + hostName);
    }
    finally {
      configGroupLock.unlock();
    }
  }

  public RequestStatusResponse installHost(String hostName, String clusterName, Collection<String> skipInstallForComponents, Collection<String> dontSkipInstallForComponents, boolean skipFailure) {
    try {
      return getHostComponentResourceProvider().install(clusterName, hostName, skipInstallForComponents,
        dontSkipInstallForComponents, skipFailure, true);
    } catch (Exception e) {
      LOG.error("INSTALL Host request submission failed:", e);
      throw new RuntimeException("INSTALL Host request submission failed: " + e, e);
    }
  }

  public RequestStatusResponse startHost(String hostName, String clusterName, Collection<String> installOnlyComponents, boolean skipFailure) {
    try {
      return getHostComponentResourceProvider().start(clusterName, hostName, installOnlyComponents, skipFailure, true);
    } catch (Exception e) {
      LOG.error("START Host request submission failed:", e);
      throw new RuntimeException("START Host request submission failed: " + e, e);
    }
  }

  /**
   * Persist cluster state for the ambari UI.  Setting this state informs that UI that a cluster has been
   * installed and started and that the monitoring screen for the cluster should be displayed to the user.
   *
   * @param clusterName  cluster name
   * @param stackId    stack ID
   */
  public void persistInstallStateForUI(String clusterName, StackId stackId) {
    String stackInfo = stackId.toString();
    final ClusterRequest clusterRequest = new ClusterRequest(null, clusterName, "INSTALLED", null, stackInfo, null);

    try {
      RetryHelper.executeWithRetry(new Callable<Object>() {
        @Override
        public Object call() throws Exception {
          getController().updateClusters(Collections.singleton(clusterRequest), null);
          return null;
        }
      });
    } catch (AmbariException e) {
      LOG.error("Unable to set install state for UI", e);
    }
  }


  public void setConfigurationOnCluster(final ClusterRequest clusterRequest) {
    try {
      RetryHelper.executeWithRetry(new Callable<Object>() {
        @Override
        public Object call() throws Exception {
          getController().updateClusters(Collections.singleton(clusterRequest), null, false);
          return null;
        }
      });
    } catch (AmbariException e) {
      LOG.error("Failed to set configurations on cluster: ", e);
      throw new RuntimeException("Failed to set configurations on cluster: " + e, e);
    }
  }

  public void notifyAgentsAboutConfigsChanges(String clusterName) {
    try {
      configHelper.get().updateAgentConfigs(Collections.singleton(clusterName));
    } catch (AmbariException e) {
      LOG.error("Failed to set send agent updates: ", e);
      throw new RuntimeException("Failed to set send agent updates: " + e, e);
    }
  }

  /**
   * Verifies that all desired configurations have reached the resolved state
   *   before proceeding with the install
   *
   * @param clusterId ID of the cluster
   * @param updatedConfigTypes set of config types that are required to be in the TOPOLOGY_RESOLVED state
   *
   * @throws AmbariException upon any system-level error that occurs
   */
  public void waitForConfigurationResolution(Long clusterId, Set<String> updatedConfigTypes) throws AmbariException {
    Cluster cluster = getController().getClusters().getCluster(clusterId);
    boolean shouldWaitForResolution = true;
    while (shouldWaitForResolution) {
      int numOfRequestsStillRequiringResolution = 0;

      // for all config types specified
      for (String actualConfigType : updatedConfigTypes) {
        // get the actual cluster config for comparison
        DesiredConfig actualConfig = cluster.getDesiredConfigs().get(actualConfigType);
        if (actualConfig == null && actualConfigType.equals("core-site")) {
          continue;
        }
        if (!actualConfig.getTag().equals(TopologyManager.TOPOLOGY_RESOLVED_TAG)) {
          // if any expected config is not resolved, deployment must wait
          LOG.info("Config type " + actualConfigType + " not resolved yet, Blueprint deployment will wait until configuration update is completed");
          numOfRequestsStillRequiringResolution++;
        } else {
          LOG.info("Config type " + actualConfigType + " is resolved in the cluster config.");
        }
      }

      if (numOfRequestsStillRequiringResolution == 0) {
        // all configs are resolved, deployment can continue
        LOG.info("All required configuration types are in the " + TopologyManager.TOPOLOGY_RESOLVED_TAG + " state.  Blueprint deployment can now continue.");
        shouldWaitForResolution = false;
      } else {
        LOG.info("Waiting for " + numOfRequestsStillRequiringResolution + " configuration types to be resolved before Blueprint deployment can continue");

        try {
          // sleep before checking the config again
          Thread.sleep(100);
        } catch (InterruptedException e) {
          LOG.warn("sleep interrupted");
        }
      }
    }
  }

  /**
   * Verifies if the given cluster has at least one desired configuration transitioned through
   * TopologyManager.INITIAL -> .... -> TopologyManager.TOPOLOGY_RESOLVED -> ....
   * @param clusterId the identifier of the cluster to be checked
   * @return true if the cluster
   */
  public boolean isTopologyResolved(long clusterId) {
    boolean isTopologyResolved = false;
    try {
      Cluster cluster = getController().getClusters().getClusterById(clusterId);

      // Check through the various cluster config versions that these transitioned through TopologyManager.INITIAL -> .... -> TopologyManager.TOPOLOGY_RESOLVED -> ....
      Map<String, Set<DesiredConfig>> allDesiredConfigsByType = cluster.getAllDesiredConfigVersions();

      for (String configType: allDesiredConfigsByType.keySet()) {
        Set<DesiredConfig> desiredConfigVersions = allDesiredConfigsByType.get(configType);

        SortedSet<DesiredConfig> desiredConfigsOrderedByVersion = new TreeSet<>(new Comparator<DesiredConfig>() {
          @Override
          public int compare(DesiredConfig o1, DesiredConfig o2) {
            if (o1.getVersion() < o2.getVersion()) {
              return -1;
            }

            if (o1.getVersion() > o2.getVersion()) {
              return 1;
            }

            return 0;
          }
        });

        desiredConfigsOrderedByVersion.addAll(desiredConfigVersions);

        int tagMatchState = 0; // 0 -> INITIAL -> tagMatchState = 1 -> TOPLOGY_RESOLVED -> tagMatchState = 2

        for (DesiredConfig config: desiredConfigsOrderedByVersion) {
          if (config.getTag().equals(TopologyManager.INITIAL_CONFIG_TAG) && tagMatchState == 0) {
            tagMatchState = 1;
          } else if (config.getTag().equals(TopologyManager.TOPOLOGY_RESOLVED_TAG) && tagMatchState == 1) {
            tagMatchState = 2;
            break;
          }
        }

        if (tagMatchState == 2) {
          isTopologyResolved = true;
          break;
        }
      }

    } catch (ClusterNotFoundException e) {
      LOG.info("Attempted to determine if configuration is topology resolved for a non-existent cluster: {}",
              clusterId);
    } catch (AmbariException e) {
      throw new RuntimeException(
              "Unable to determine if cluster config is topology resolved due to unknown error: " + e, e);
    }

    return isTopologyResolved;
  }

  public PersistedState getPersistedTopologyState() {
    return persistedState;
  }

  public boolean isHostRegisteredWithCluster(long clusterId, String host) {
    boolean found = false;
    try {
      Collection<Host> hosts = getController().getClusters().getClusterById(clusterId).getHosts();
      for (Host h : hosts) {
        if (h.getHostName().equals(host)) {
          found = true;
          break;
        }
      }
    } catch (AmbariException e) {
      throw new RuntimeException(String.format("Unable to get hosts for cluster ID = %s: %s", clusterId, e), e);
    }
    return found;
  }

  public long getClusterId(String clusterName) throws AmbariException {
    return getController().getClusters().getCluster(clusterName).getClusterId();
  }

  public String getClusterName(long clusterId) throws AmbariException {
    return getController().getClusters().getClusterById(clusterId).getClusterName();
  }

  /**
   * Add the new host to an existing config group.
   */
  private boolean addHostToExistingConfigGroups(String hostName, ClusterTopology topology, String configGroupName) {
    boolean addedHost = false;
    Clusters clusters;
    Cluster cluster;
    try {
      clusters = getController().getClusters();
      cluster = clusters.getClusterById(topology.getClusterId());
    } catch (AmbariException e) {
      throw new RuntimeException(String.format(
          "Attempt to add hosts to a non-existent cluster: '%s'", topology.getClusterId()));
    }
    // I don't know of a method to get config group by name
    //todo: add a method to get config group by name
    Map<Long, ConfigGroup> configGroups = cluster.getConfigGroups();
    for (ConfigGroup group : configGroups.values()) {
      if (group.getName().equals(configGroupName)) {
        try {
          Host host = clusters.getHost(hostName);
          addedHost = true;
          if (! group.getHosts().containsKey(host.getHostId())) {
            group.addHost(host);
          }

        } catch (AmbariException e) {
          // shouldn't occur, this host was just added to the cluster
          throw new RuntimeException(String.format(
              "An error occurred while registering host '%s' with config group '%s' ", hostName, group.getName()), e);
        }
      }
    }
    return addedHost;
  }

  /**
   * Register config groups for host group scoped configuration.
   * For each host group with configuration specified in the blueprint, a config group is created
   * and the hosts associated with the host group are assigned to the config group.
   */
  private void createConfigGroupsAndRegisterHost(ClusterTopology topology, String groupName) throws AmbariException {
    Map<String, Map<String, Config>> groupConfigs = new HashMap<>();
    StackDefinition stack = topology.getStack();

    // get the host-group config with cluster creation template overrides
    Configuration topologyHostGroupConfig = topology.
        getHostGroupInfo().get(groupName).getConfiguration();

    // only get user provided configuration for host group which includes only CCT/HG and BP/HG properties
    Map<String, Map<String, String>> userProvidedGroupProperties =
        topologyHostGroupConfig.getFullProperties(1);

    // iterate over topo host group configs which were defined in
    for (Map.Entry<String, Map<String, String>> entry : userProvidedGroupProperties.entrySet()) {
      String type = entry.getKey();
      String service = stack.getServicesForConfigType(type)
        .filter(each -> topology.getServiceTypes().contains(each))
        .findFirst()
        // TODO check if this is required at all (might be handled by the "orphan" removal)
        // TODO move this validation earlier
        .orElseThrow(() -> new IllegalArgumentException("Specified configuration type is not associated with any service in the blueprint: " + type));

      Config config = configFactory.createReadOnly(type, groupName, entry.getValue(), null);
      //todo: attributes
      Map<String, Config> serviceConfigs = groupConfigs.get(service);
      if (serviceConfigs == null) {
        serviceConfigs = new HashMap<>();
        groupConfigs.put(service, serviceConfigs);
      }
      serviceConfigs.put(type, config);
    }

    String bpName = topology.getBlueprintName();
    for (Map.Entry<String, Map<String, Config>> entry : groupConfigs.entrySet()) {
      String service = entry.getKey();
      Map<String, Config> serviceConfigs = entry.getValue();
      String absoluteGroupName = getConfigurationGroupName(bpName, groupName);
      Collection<String> groupHosts;

      groupHosts = topology.getHostGroupInfo().
          get(groupName).getHostNames();

      // remove hosts that are not assigned to the cluster yet
      String clusterName = null;
      try {
        clusterName = getClusterName(topology.getClusterId());
      } catch (AmbariException e) {
        LOG.error("Cannot get cluster name for clusterId = " + topology.getClusterId(), e);
        throw new RuntimeException(e);
      }

      final Map<String, Host> clusterHosts = getController().getClusters().getHostsForCluster(clusterName);
      Iterable<String> filteredGroupHosts = Iterables.filter(groupHosts, new com.google.common.base.Predicate<String>() {
        @Override
        public boolean apply(@Nullable String groupHost) {
          return clusterHosts.containsKey(groupHost);
        }
      });

      ConfigGroupRequest request = new ConfigGroupRequest(null, clusterName,
        absoluteGroupName, service, DEFAULT_SERVICE_GROUP_NAME, service, "Host Group Configuration",
        Sets.newHashSet(filteredGroupHosts), serviceConfigs);

      // get the config group provider and create config group resource
      ConfigGroupResourceProvider configGroupProvider = (ConfigGroupResourceProvider)
          getClusterController().ensureResourceProvider(Resource.Type.ConfigGroup);

      try {
        configGroupProvider.createResources(Collections.singleton(request));
      } catch (Exception e) {
        LOG.error("Failed to create new configuration group: " + e);
        throw new RuntimeException("Failed to create new configuration group: " + e, e);
      }
    }
  }

  public StackDefinition composeStacks(Set<StackId> stackIds) {
    Set<Stack> stacks = stackIds.stream()
      .map(this::createStack)
      .collect(toSet());

    return StackDefinition.of(stacks);
  }

  protected Stack createStack(StackId stackId) {
    try {
      return stackFactory.createStack(stackId);
    } catch (StackAccessException e) {
      throw new NoSuchStackException(stackId);
    }
  }

  /**
   * Get a config group name based on a bp and host group.
   *
   * @param bpName        blueprint name
   * @param hostGroupName host group name
   * @return config group name
   */
  private String getConfigurationGroupName(String bpName, String hostGroupName) {
    return String.format("%s:%s", bpName, hostGroupName);
  }

  /**
   * Gets an instance of {@link ConfigHelper} for classes which are not
   * dependency injected.
   *
   * @return a {@link ConfigHelper} instance.
   */
  public ConfigHelper getConfigHelper() {
    return configHelper.get();
  }

  private synchronized HostResourceProvider getHostResourceProvider() {
    if (hostResourceProvider == null) {
      hostResourceProvider = (HostResourceProvider)
          ClusterControllerHelper.getClusterController().ensureResourceProvider(Resource.Type.Host);

    }
    return hostResourceProvider;
  }

  private synchronized HostComponentResourceProvider getHostComponentResourceProvider() {
    if (hostComponentResourceProvider == null) {
      hostComponentResourceProvider = (HostComponentResourceProvider)
          ClusterControllerHelper.getClusterController().ensureResourceProvider(Resource.Type.HostComponent);

    }
    return hostComponentResourceProvider;
  }

  private synchronized ServiceGroupResourceProvider getServiceGroupResourceProvider() {
    if (serviceGroupResourceProvider == null) {
      serviceGroupResourceProvider = (ServiceGroupResourceProvider) ClusterControllerHelper.
      getClusterController().ensureResourceProvider(Resource.Type.ServiceGroup);
    }
    return serviceGroupResourceProvider;
  }

  private synchronized ServiceGroupDependencyResourceProvider getServiceGroupDependencyResourceProvider() {
    if (serviceGroupDependencyResourceProvider == null) {
      serviceGroupDependencyResourceProvider = (ServiceGroupDependencyResourceProvider) ClusterControllerHelper.
              getClusterController().ensureResourceProvider(Resource.Type.ServiceGroupDependency);
    }
    return serviceGroupDependencyResourceProvider;
  }

  private synchronized ServiceDependencyResourceProvider getServiceDependencyResourceProvider() {
    if (serviceDependencyResourceProvider == null) {
      serviceDependencyResourceProvider = (ServiceDependencyResourceProvider) ClusterControllerHelper.
              getClusterController().ensureResourceProvider(Resource.Type.ServiceGroup);
    }
    return serviceDependencyResourceProvider;
  }

  private synchronized ServiceResourceProvider getServiceResourceProvider() {
    if (serviceResourceProvider == null) {
      serviceResourceProvider = (ServiceResourceProvider) ClusterControllerHelper.
          getClusterController().ensureResourceProvider(Resource.Type.Service);
    }
    return serviceResourceProvider;
  }

  private synchronized ComponentResourceProvider getComponentResourceProvider() {
    if (componentResourceProvider == null) {
      componentResourceProvider = (ComponentResourceProvider) ClusterControllerHelper.
          getClusterController().ensureResourceProvider(Resource.Type.Component);
    }
    return componentResourceProvider;
  }
}
