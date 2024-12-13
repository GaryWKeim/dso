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
package com.tc.admin;

import org.junit.Test;
import org.mockito.ArgumentMatcher;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TCStopTest {
  @Test(expected = IOException.class)
  public void testAuthenticationFailure() throws Exception {
    WebTarget target = mockWebTarget("localhost", 12323);
    responseCode(target, 401);
    TCStop.restStop(target, false);
  }

  @Test(expected = IOException.class)
  public void testFourOhFour() throws Exception {
    WebTarget target = mockWebTarget("localhost", 12323);
    responseCode(target, 404);
    TCStop.restStop(target, false);
  }

  @Test
  public void testForceStop() throws Exception {
    WebTarget target = mockWebTarget("localhost", 12323);
    responseCode(target, 200);
    TCStop.restStop(target, true);
    verify(target.request(MediaType.APPLICATION_JSON_TYPE)).post(
        argThat(entityWithContent(true, MediaType.APPLICATION_JSON_TYPE)));
  }

  @Test
  public void testNoForceStop() throws Exception {
    WebTarget target = mockWebTarget("localhost", 12323);
    responseCode(target, 200);
    TCStop.restStop(target, false);
    verify(target.request(MediaType.APPLICATION_JSON_TYPE)).post(
        argThat(entityWithContent(false, MediaType.APPLICATION_JSON_TYPE)));
  }

  @Test
  public void testUnknownError() throws Exception {
    WebTarget target = mockWebTarget("localhost", 12323);
    responseCode(target, 403);
    String errorMessage = "Insufficient permissions to perform a server shutdown.";
    when(target.request(MediaType.APPLICATION_JSON_TYPE)
        .post(Entity.json(null))
        .readEntity(any(Class.class))).thenReturn(
        new HashMap<String, String>(){{
          put("error", errorMessage);
          // important since TCStop will try to read it
          put("stackTrace", "");
        }}
    );
    try {
      TCStop.restStop(target, false);
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage(), containsString(errorMessage));
    }
  }

  private void responseCode(WebTarget target, int responseCode) {
    when(target.request(MediaType.APPLICATION_JSON_TYPE).post(Entity.json(null)).getStatus()).thenReturn(responseCode);
  }

  private WebTarget mockWebTarget(String host, int port) throws URISyntaxException {
    Response response = mock(Response.class);
    when(response.readEntity(Boolean.class)).thenReturn(Boolean.TRUE);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(builder.post(any(Entity.class))).thenReturn(response);
    WebTarget target = mock(WebTarget.class);
    when(target.getUri()).thenReturn(new URI("http://" + host + ":" + port));
    when(target.path(anyString())).thenReturn(target);
    when(target.request((MediaType) any())).thenReturn(builder);
    return target;
  }

  private static <T> ArgumentMatcher<Entity<T>> entityWithContent(final boolean expected, final MediaType mediaType) {
    return new ArgumentMatcher<Entity<T>>() {
      @Override
      public boolean matches(final Entity<T> argument) {
        if (argument instanceof Entity) {
          Entity match = (Entity) argument;
          if (!(match.getEntity() instanceof Map)) return false;
          return match.getMediaType() == mediaType && ((Map)match.getEntity()).get("forceStop").equals(expected);
        }
        return false;
      }
    };
  }
}