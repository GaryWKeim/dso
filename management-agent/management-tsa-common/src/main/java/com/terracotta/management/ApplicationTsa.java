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
package com.terracotta.management;

import org.glassfish.jersey.media.sse.SseFeature;

import com.terracotta.management.web.proxy.ProxyExceptionMapper;
import com.terracotta.management.web.proxy.ContainerRequestContextFilter;

import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;

import jakarta.ws.rs.core.Application;

public class ApplicationTsa extends Application {

  @Override
  public Set<Class<?>> getClasses() {
    Set<Class<?>> s = new HashSet<Class<?>>();
    s.add(SseFeature.class);
    s.add(ProxyExceptionMapper.class);
    s.add(ContainerRequestContextFilter.class);
    ServiceLoader<ApplicationTsaService> loader = ServiceLoader.load(ApplicationTsaService.class);
    for (ApplicationTsaService applicationTsaService : loader) {
      s.addAll(applicationTsaService.getResourceClasses());
    }
    return s;
  }

}