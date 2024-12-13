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
package com.tc.server.util;

import java.util.Collections;
import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ServerStatTest {
  @Test
  public void testNormal() throws Exception {
    WebTarget target = mockWebTarget("foo", 9889);
    Map<String, Object> responseMap = new HashMap<String, Object>();
    responseMap.put("name", "foo");
    responseMap.put("serverGroupName", "blahblah");
    responseMap.put("initialState", "somestate");
    responseMap.put("state", "somestate");
    responseMap.put("role", "paper pusher");
    responseMap.put("health", "great");
    when(target.request(MediaType.APPLICATION_JSON_TYPE).get().getStatus()).thenReturn(200);
    when(target.request(MediaType.APPLICATION_JSON_TYPE)
        .get()
        .readEntity(any(Class.class))).thenReturn(responseMap);
    ServerStat stats = ServerStat.getStats(target);
    assertThat(stats.getGroupName(), is(responseMap.get("serverGroupName")));
    assertThat(stats.getState(), is(responseMap.get("initialState")));
    assertThat(stats.getState(), is(responseMap.get("state")));
    assertThat(stats.getRole(), is(responseMap.get("role")));
    assertThat(stats.getHealth(), is(responseMap.get("health")));
  }

  @Test
  public void testUnknownError() throws Exception {
    String errorMessage = "this is an error message 29138491283749skjafhdkasj";
    WebTarget target = mockWebTarget("host", 4321);
    when(target.request(MediaType.APPLICATION_JSON_TYPE).get().getStatus()).thenReturn(403);
    when(target.request(MediaType.APPLICATION_JSON_TYPE)
            .get()
            .readEntity(any(Class.class))).thenReturn(Collections.singletonMap("error", errorMessage));
    ServerStat stats = ServerStat.getStats(target);
    assertErrorStats(stats);
    assertThat(stats.toString(), containsString(errorMessage));
  }

  @Test
  public void testFourOhFour() throws Exception {
    WebTarget target = mockWebTarget("host", 4321);
    when(target.request(MediaType.APPLICATION_JSON_TYPE).get().getStatus()).thenReturn(404);
    ServerStat stats = ServerStat.getStats(target);
    assertErrorStats(stats);
  }

  @Test
  public void testAuthenticationFailure() throws Exception {
    WebTarget target = mockWebTarget("localhost", 1234);
    when(target.request(MediaType.APPLICATION_JSON_TYPE).get().getStatus()).thenReturn(401);
    ServerStat stats = ServerStat.getStats(target);
    assertErrorStats(stats);
  }

  private void assertErrorStats(ServerStat stats) {
    assertThat(stats.getGroupName(), is("unknown"));
    assertThat(stats.getHealth(), is("unknown"));
    assertThat(stats.getRole(), is("unknown"));
    assertThat(stats.getInitialState(), is("unknown"));
    assertThat(stats.getState(), is("unknown"));
    assertThat(stats.getErrorMessage(), notNullValue());
  }

  private WebTarget mockWebTarget(String host, int port) throws URISyntaxException {
    Response response = mock(Response.class);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(builder.get()).thenReturn(response);
    WebTarget target = mock(WebTarget.class);
    when(target.getUri()).thenReturn(new URI("http://" + host + ":" + port));
    when(target.path(anyString())).thenReturn(target);
    when(target.request((MediaType[]) any())).thenReturn(builder);
    return target;
  }
}