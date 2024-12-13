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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.management.ServiceExecutionException;
import org.terracotta.management.ServiceLocator;
import org.terracotta.management.resource.ResponseEntityV2;
import org.terracotta.management.resource.exceptions.ResourceRuntimeException;
import org.terracotta.management.resource.services.validator.RequestValidator;

import com.terracotta.management.resource.LicenseEntityV2;
import com.terracotta.management.resource.services.utils.UriInfoUtils;
import com.terracotta.management.service.LicenseServiceV2;

import java.util.Set;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * A resource service for querying TSA license properties
 * 
 * @author Hung Huynh
 */
@Path("/v2/agents/licenseProperties")
public class LicenseResourceServiceImplV2 {

  private static final Logger           LOG = LoggerFactory.getLogger(LicenseResourceServiceImplV2.class);

  private final RequestValidator        requestValidator;
  private final LicenseServiceV2        licenseService;

  public LicenseResourceServiceImplV2() {
    this.requestValidator = ServiceLocator.locate(RequestValidator.class);
    this.licenseService = ServiceLocator.locate(LicenseServiceV2.class);
  }

  /**
   * Get a {@code Collection} of {@link LicenseServiceV2} objects
   * 
   * @return a collection of {@link LicenseServiceV2} objects.
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public ResponseEntityV2<LicenseEntityV2> getLicenseProperties(@Context
  UriInfo info) {
    LOG.debug(String.format("Invoking LicenseResourceServiceImplV2.getLicenseProperties: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    try {
      Set<String> serverNames = UriInfoUtils.extractLastSegmentMatrixParameterAsSet(info, "serverNames");
      return licenseService.getLicenseProperties(serverNames);
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to get license properties", see,
                                         Response.Status.BAD_REQUEST.getStatusCode());
    }
  }
}
