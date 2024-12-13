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
package com.terracotta.management.web.proxy;

import org.glassfish.jersey.server.ContainerRequest;
import org.terracotta.management.ServiceLocator;
import org.terracotta.management.resource.exceptions.ExceptionUtils;

import com.terracotta.management.service.impl.util.RemoteManagementSource;

import java.net.URI;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Proxies a request to a different server.
 *
 * @author Ludovic Orban
 */
@Provider
public class ProxyExceptionMapper implements ExceptionMapper<ProxyException> {

  private final RemoteManagementSource remoteManagementSource = ServiceLocator.locate(RemoteManagementSource.class);

  @Override
  public Response toResponse(ProxyException exception) {
    ContainerRequestContext request = ContainerRequestContextFilter.CONTAINER_REQUEST_CONTEXT_THREAD_LOCAL.get();
    String activeL2Url = exception.getActiveL2WithMBeansUrl();
    URI uri = request.getUriInfo().getRequestUri();
    String method = request.getMethod();
    URI uriToGo = UriBuilder.fromUri(activeL2Url).path(uri.getPath()).replaceQuery(uri.getQuery()).build();

    // only add the "Accept-Encoding" header on the proxy request when the original request contains
    // them, otherwise we're going to stream compressed data to a client which may not support that.
    String acceptEncoding = request.getHeaders().getFirst("Accept-Encoding");
    boolean compress = acceptEncoding != null && (acceptEncoding.contains("gzip") || acceptEncoding.contains("deflate"));

    if ("GET".equals(method)) {
      return buildResponse(remoteManagementSource.resource(uriToGo, compress).get());
    } else if ("POST".equals(method)) {
      byte[] e = ((ContainerRequest)request).readEntity(byte[].class);
      return buildResponse(remoteManagementSource.resource(uriToGo, compress).post(Entity.entity(e, request.getMediaType())));
    } else if ("PUT".equals(method)) {
      byte[] e = ((ContainerRequest)request).readEntity(byte[].class);
      return buildResponse(remoteManagementSource.resource(uriToGo, compress).put(Entity.entity(e, request.getMediaType())));
    } else if ("DELETE".equals(method)) {
      return buildResponse(remoteManagementSource.resource(uriToGo, compress).delete());
    } else {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .type(MediaType.APPLICATION_JSON_TYPE)
          .entity(ExceptionUtils.toErrorEntity(new Exception("Cannot proxy " + method + " HTTP method"))).build();
    }
  }

  private Response buildResponse(Response response) {
    return Response.fromResponse(response)
        .entity(response.readEntity(byte[].class))
        .build();
  }

}
