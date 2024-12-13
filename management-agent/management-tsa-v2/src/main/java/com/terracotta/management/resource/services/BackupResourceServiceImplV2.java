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

import com.terracotta.management.resource.BackupEntityV2;
import com.terracotta.management.resource.services.utils.UriInfoUtils;
import com.terracotta.management.service.BackupServiceV2;

import java.util.Set;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * A resource service for performing TSA backups.
 * 
 * @author Ludovic Orban
 */
@Path("/v2/agents/backups")
public class BackupResourceServiceImplV2 {

  private static final Logger LOG = LoggerFactory.getLogger(BackupResourceServiceImplV2.class);

  private final BackupServiceV2 backupService;
  private final RequestValidator requestValidator;

  public BackupResourceServiceImplV2() {
    this.backupService = ServiceLocator.locate(BackupServiceV2.class);
    this.requestValidator = ServiceLocator.locate(RequestValidator.class);
  }

  public final static String ATTR_BACKUP_NAME_KEY = "name";

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public ResponseEntityV2<BackupEntityV2> getBackupStatus(@Context UriInfo info) {
    LOG.debug(String.format("Invoking BackupResourceServiceImplV2.getBackupStatus: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    Set<String> serverNames = UriInfoUtils.extractLastSegmentMatrixParameterAsSet(info, "serverNames");

    try {
      return backupService.getBackupStatus(serverNames);
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to get TSA backup status", see, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  public ResponseEntityV2<BackupEntityV2> backup(@Context UriInfo info) {
    LOG.debug(String.format("Invoking BackupResourceServiceImplV2.backup: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    Set<String> serverNames = UriInfoUtils.extractLastSegmentMatrixParameterAsSet(info, "serverNames");

    MultivaluedMap<String, String> qParams = info.getQueryParameters();
    String backupName = qParams.getFirst(ATTR_BACKUP_NAME_KEY);

    try {
      return backupService.backup(serverNames, backupName);
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to perform TSA backup", see, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }
}
