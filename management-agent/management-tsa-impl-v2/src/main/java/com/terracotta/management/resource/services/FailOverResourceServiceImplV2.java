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

import org.terracotta.management.ServiceLocator;

import com.tc.config.schema.setup.FailOverAction;
import com.tc.l2.state.sbp.InvalidOperationException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.terracotta.management.service.FailOverServiceV2;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * A resource service for performing local fail-over actions.
 */
@Path("/v2/local/failover")
public class FailOverResourceServiceImplV2 {

  private static final TCLogger logger = TCLogging.getLogger(FailOverResourceServiceImplV2.class);

  private final FailOverServiceV2 failOverService;

  public FailOverResourceServiceImplV2() {
    this.failOverService = ServiceLocator.locate(FailOverServiceV2.class);
  }

  @POST
  @Path("/promote")
  @Produces(MediaType.APPLICATION_JSON)
  public Response promote() {
    return performFailOverAction(FailOverAction.PROMOTE);
  }

  @POST
  @Path("/restart")
  @Produces(MediaType.APPLICATION_JSON)
  public Response restart() {
    return performFailOverAction(FailOverAction.RESTART);
  }

  @POST
  @Path("/failFast")
  @Produces(MediaType.APPLICATION_JSON)
  public Response failFast() {
    return performFailOverAction(FailOverAction.FAILFAST);
  }
  
  private Response performFailOverAction(FailOverAction action) {
    logger.info("Performing " + action + " fail-over action");
    try {
      switch (action) {
        case PROMOTE:
          failOverService.promote();
          break;
        case RESTART:
          failOverService.restart();
          break;
        case FAILFAST:
          failOverService.failFast();
          break;
        default:
          throw new UnsupportedOperationException("Unsupported fail-over action:" + action);
      }
      return Response.ok().build();
    } catch (InvalidOperationException ioe) {
      return Response.status(Response.Status.NOT_FOUND).entity(ioe.getMessage()).build();
    }
  }

}
