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
package com.terracotta.management.service.impl.util;

import org.terracotta.management.resource.ErrorEntity;

import com.terracotta.management.web.proxy.ProxyException;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * @author Ludovic Orban
 */
public class L1MBeansSourceUtils {

  public static void proxyClientRequest(String activeL2WithMBeansUrls) throws ProxyException, WebApplicationException {
    if (activeL2WithMBeansUrls == null) {
      ErrorEntity errorEntity = new ErrorEntity();
      errorEntity.setError("No management coordinator");
      errorEntity.setDetails("No server is in the ACTIVE-COORDINATOR state in the coordinator group, try again later.");
      throw new WebApplicationException(Response.status(400).entity(errorEntity).build());
    }
    throw new ProxyException(activeL2WithMBeansUrls);
  }

}
