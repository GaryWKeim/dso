/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.terracotta.management.resource.services;

import org.terracotta.management.ServiceExecutionException;
import org.terracotta.management.ServiceLocator;
import org.terracotta.management.resource.exceptions.ResourceRuntimeException;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.beans.TCServerInfoMBean;
import com.tc.management.beans.TCServerInfoMBean.RestartMode;
import com.tc.management.beans.UnexpectedStateException;
import com.tc.server.TCServer;
import com.terracotta.management.resource.StopEntityV2;
import com.terracotta.management.resource.ServerEntityV2;
import com.terracotta.management.resource.ServerGroupEntityV2;
import com.terracotta.management.resource.TopologyEntityV2;
import com.terracotta.management.service.TopologyServiceV2;
import com.terracotta.management.service.impl.util.LocalManagementSource;

import java.util.Collection;
import java.util.Set;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * A resource service for performing local server shutdown.
 * 
 * @author Ludovic Orban
 */
@Path("/v2/local/shutdown")
public class LocalShutdownResourceServiceImplV2 {

  private static final TCLogger LOG = TCLogging.getLogger(LocalShutdownResourceServiceImplV2.class);

  private final TopologyServiceV2 topologyService;
  private final LocalManagementSource localManagementSource = new LocalManagementSource();

  public LocalShutdownResourceServiceImplV2() {
    this.topologyService = ServiceLocator.locate(TopologyServiceV2.class);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response shutdown(@Context UriInfo info, StopEntityV2 stopConfig) {
    LOG.info(String.format("Invoking shutdown: %s", info.getRequestUri()));

    try {
      if (stopConfig != null && !stopConfig.isForceStop() && !isPassiveStandbyAvailable() && localManagementSource.isLegacyProductionModeEnabled()) {
        String errorMessage = "No passive server available in Standby mode. Use force option to stop the server.";
        LOG.debug(errorMessage);
        throw new ResourceRuntimeException(errorMessage, Response.Status.BAD_REQUEST.getStatusCode());
      }
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to shutdown TSA", see, Response.Status.BAD_REQUEST.getStatusCode());
    }

    boolean stopIfPassive = false;
    boolean stopIfActive = false;
    RestartMode restartMode = RestartMode.STOP_ONLY;

    if (stopConfig != null) {
      stopIfActive = stopConfig.isStopIfActive();
      stopIfPassive = stopConfig.isStopIfPassive();
      if (stopConfig.isRestart()) {
        restartMode = RestartMode.STOP_AND_RESTART;
      } else if (stopConfig.isRestartInSafeMode()) {
        restartMode = RestartMode.STOP_AND_RESTART_IN_SAFE_MODE;
      }
    }

    try {
      if (stopIfActive) {
        localManagementSource.shutdownServerIfActive(restartMode);
      } else if (stopIfPassive) {
        localManagementSource.shutdownServerIfPassive(restartMode);
      } else {
        localManagementSource.shutdownServer(restartMode);
      }
    } catch (UnexpectedStateException e) {
      return Response.ok(false).build();
    }

    return Response.ok(true).build();
  }

  private boolean isPassiveStandbyAvailable() throws ServiceExecutionException {
    ServerGroupEntityV2 currentServerGroup = getCurrentServerGroup();
    if(currentServerGroup == null){
      return false;
    }
    for (ServerEntityV2 serverEntity : currentServerGroup.getServers()) {
      if ("PASSIVE-STANDBY".equals(serverEntity.getAttributes().get("State"))) {
        return true;
      }
    }
    return false;
  }

  private ServerGroupEntityV2 getCurrentServerGroup() throws ServiceExecutionException {
    String localServerName = localManagementSource.getLocalServerName();
    Collection<TopologyEntityV2> serverTopologies = topologyService.getServerTopologies(null).getEntities();
    for (TopologyEntityV2 serverTopology : serverTopologies) {
      Set<ServerGroupEntityV2> serverGroups = serverTopology.getServerGroupEntities();
      for (ServerGroupEntityV2 serverGroup : serverGroups) {
        Set<ServerEntityV2> servers = serverGroup.getServers();
        for (ServerEntityV2 server : servers) {
          if (server.getAttributes().get("Name").equals(localServerName)) {
            return serverGroup;
          }
        }
      }
    }
    return null;
  }

}
