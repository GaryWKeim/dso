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

import java.io.IOException;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;

/**
 * @author Ludovic Orban
 */
public class ContainerRequestContextFilter implements ContainerRequestFilter, ContainerResponseFilter {

  public static final ThreadLocal<ContainerRequestContext> CONTAINER_REQUEST_CONTEXT_THREAD_LOCAL = new ThreadLocal<ContainerRequestContext>();

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    ((ContainerRequest)requestContext).bufferEntity();
    CONTAINER_REQUEST_CONTEXT_THREAD_LOCAL.set(requestContext);
  }

  @Override
  public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
    CONTAINER_REQUEST_CONTEXT_THREAD_LOCAL.remove();
  }

}
