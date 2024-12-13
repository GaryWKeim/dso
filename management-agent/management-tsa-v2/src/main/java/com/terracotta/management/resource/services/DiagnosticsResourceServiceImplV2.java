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

import com.terracotta.management.resource.ThreadDumpEntityV2;
import com.terracotta.management.resource.TopologyReloadStatusEntityV2;
import com.terracotta.management.resource.services.utils.UriInfoUtils;
import com.terracotta.management.service.DiagnosticsServiceV2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import static com.terracotta.management.resource.services.utils.AttachmentUtils.createTimestampedZipFilename;

/**
 * A resource service for performing TSA diagnostics.
 * 
 * @author Ludovic Orban
 */
@Path("/v2/agents/diagnostics")
public class DiagnosticsResourceServiceImplV2 {

  private static final Logger LOG = LoggerFactory.getLogger(DiagnosticsResourceServiceImplV2.class);

  private final DiagnosticsServiceV2 diagnosticsService;
  private final RequestValidator requestValidator;

  public DiagnosticsResourceServiceImplV2() {
    this.diagnosticsService = ServiceLocator.locate(DiagnosticsServiceV2.class);
    this.requestValidator = ServiceLocator.locate(RequestValidator.class);
  }

  private InputStream zipAndConvertToInputStream(Collection<ThreadDumpEntityV2> threadDumpEntities) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ZipOutputStream out = new ZipOutputStream(baos);

    for (ThreadDumpEntityV2 threadDumpEntityV2 : threadDumpEntities) {
      out.putNextEntry(new ZipEntry(threadDumpEntityV2.getSourceId().replace(':', '_') + ".txt"));
      out.write(threadDumpEntityV2.getDump().getBytes(Charset.forName("UTF-8")));
      out.closeEntry();
    }
    out.close();

    return new ByteArrayInputStream(baos.toByteArray());
  }

  @GET
  @Path("/threadDump")
  @Produces(MediaType.APPLICATION_JSON)
  public ResponseEntityV2<ThreadDumpEntityV2> clusterThreadDump(@Context UriInfo info) {
    LOG.debug(String.format("Invoking DiagnosticsResourceServiceImplV2.clusterThreadDump: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    try {
      Set<String> productIDs = UriInfoUtils.extractProductIds(info);
      return diagnosticsService.getClusterThreadDump(productIDs);
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to perform TSA diagnostics", see, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @GET
  @Path("/threadDumpArchive")
  @Produces("application/zip")
  public Response clusterThreadDumpZipped(@Context UriInfo info) {
    Collection<ThreadDumpEntityV2> threadDumpEntities = clusterThreadDump(info).getEntities();

    try {
      InputStream inputStream = zipAndConvertToInputStream(threadDumpEntities);
      return Response.ok().entity(inputStream).header("Content-Disposition", "attachment; filename=" + createTimestampedZipFilename("clusterThreadDump")).build();
    } catch (IOException ioe) {
      throw new ResourceRuntimeException("Failed to perform TSA diagnostics", ioe, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @GET
  @Path("/threadDump/servers")
  @Produces(MediaType.APPLICATION_JSON)
  public ResponseEntityV2<ThreadDumpEntityV2> serversThreadDump(@Context UriInfo info) {
    LOG.debug(String.format("Invoking DiagnosticsResourceServiceImplV2.serversThreadDump: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    try {
      Set<String> serverNames = UriInfoUtils.extractLastSegmentMatrixParameterAsSet(info, "names");

      return diagnosticsService.getServersThreadDump(serverNames);
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to perform TSA diagnostics", see, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @GET
  @Path("/threadDumpArchive/servers")
  @Produces("application/zip")
  public Response serversThreadDumpZipped(@Context UriInfo info) {
    Collection<ThreadDumpEntityV2> threadDumpEntities = serversThreadDump(info).getEntities();

    try {
      InputStream inputStream = zipAndConvertToInputStream(threadDumpEntities);
      return Response.ok().entity(inputStream).header("Content-Disposition", "attachment; filename=" + createTimestampedZipFilename("serversThreadDump")).build();
    } catch (IOException ioe) {
      throw new ResourceRuntimeException("Failed to perform TSA diagnostics", ioe, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @GET
  @Path("/threadDump/clients")
  @Produces(MediaType.APPLICATION_JSON)
  public ResponseEntityV2<ThreadDumpEntityV2> clientsThreadDump(@Context UriInfo info) {
    LOG.debug(String.format("Invoking DiagnosticsResourceServiceImplV2.clientsThreadDump: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    try {
      Set<String> clientIds = UriInfoUtils.extractLastSegmentMatrixParameterAsSet(info, "ids");
      Set<String> productIDs = UriInfoUtils.extractProductIds(info);
      return diagnosticsService.getClientsThreadDump(clientIds, productIDs);
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to perform TSA diagnostics", see, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @GET
  @Path("/threadDumpArchive/clients")
  @Produces("application/zip")
  public Response clientsThreadDumpZipped(@Context UriInfo info) {
    Collection<ThreadDumpEntityV2> threadDumpEntities = clientsThreadDump(info).getEntities();

    try {
      InputStream inputStream = zipAndConvertToInputStream(threadDumpEntities);
      return Response.ok().entity(inputStream).header("Content-Disposition", "attachment; filename=" + createTimestampedZipFilename("clientsThreadDump")).build();
    } catch (IOException ioe) {
      throw new ResourceRuntimeException("Failed to perform TSA diagnostics", ioe, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @POST
  @Path("/dgc")
  @Produces(MediaType.APPLICATION_JSON)
  public boolean runDgc(@Context UriInfo info) {
    LOG.debug(String.format("Invoking DiagnosticsResourceServiceImplV2.runDgc: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    try {
      Set<String> serverNames = UriInfoUtils.extractLastSegmentMatrixParameterAsSet(info, "serverNames");

      return diagnosticsService.runDgc(serverNames);
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to perform TSA diagnostics", see, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @POST
  @Path("/dumpClusterState")
  @Produces(MediaType.APPLICATION_JSON)
  public boolean dumpClusterState(@Context UriInfo info) {
    LOG.debug(String.format("Invoking DiagnosticsResourceServiceImplV2.dumpClusterState: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    try {
      Set<String> serverNames = UriInfoUtils.extractLastSegmentMatrixParameterAsSet(info, "serverNames");

      return diagnosticsService.dumpClusterState(serverNames);
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to perform TSA diagnostics", see, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @POST
  @Path("/reloadConfiguration")
  @Produces(MediaType.APPLICATION_JSON)
  public ResponseEntityV2<TopologyReloadStatusEntityV2> reloadConfiguration(@Context UriInfo info) {
    LOG.debug(String.format("Invoking DiagnosticsResourceServiceImplV2.reloadConfiguration: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    try {
      Set<String> serverNames = UriInfoUtils.extractLastSegmentMatrixParameterAsSet(info, "serverNames");

      return diagnosticsService.reloadConfiguration(serverNames);
    } catch (ServiceExecutionException see) {
      throw new ResourceRuntimeException("Failed to perform TSA diagnostics", see, Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

}
