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

import org.glassfish.jersey.media.sse.EventInput;
import org.glassfish.jersey.media.sse.InboundEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.terracotta.management.resource.Representable;
import org.terracotta.management.resource.SubGenericType;

import com.terracotta.management.service.impl.TimeoutServiceImpl;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Ludovic Orban
 */
public class RemoteManagementSourceTest {
  private ExecutorService executorService;
  private final long defaultConnectionTimeout = 1_000;

  @Before
  public void setUp() throws Exception {
    executorService = Executors.newSingleThreadExecutor();
  }

  @After
  public void tearDown() throws Exception {
    executorService.shutdown();
  }

  @Test
  public void testGetFromRemoteL2_works() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client);
    remoteManagementSource.getFromRemoteL2("server1", new URI("/xyz"), Collection.class, String.class);

    verify(client).target(eq(new URI("http://server-host1:9540/xyz")));

    verify(builder).get(eq(new SubGenericType<Collection, String>(Collection.class, String.class)));
  }

  @Test
  public void testGetFromRemoteL2_throwsException() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    when(builder.get(any(GenericType.class))).thenThrow(WebApplicationException.class);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client);
    try {
      remoteManagementSource.getFromRemoteL2("server1", new URI("/xyz"), Collection.class, String.class);
      fail("expected ManagementSourceException");
    } catch (ManagementSourceException mse) {
      assertNotNull(mse.getErrorEntity());
    }

    verify(client).target(eq(new URI("http://server-host1:9540/xyz")));
    verify(builder).get(eq(new SubGenericType<Collection, String>(Collection.class, String.class)));
  }

  @Test
  public void testPost1ToRemoteL2_works() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client);
    remoteManagementSource.postToRemoteL2("server1", new URI("/xyz"));

    verify(client).target(eq(new URI("http://server-host1:9540/xyz")));
    verify(builder).post(Mockito.<Entity<Object>>eq(null));
  }

  @Test
  public void testPost1ToRemoteL2_fails() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    when(builder.post(any())).thenThrow(WebApplicationException.class);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client);
    try {
      remoteManagementSource.postToRemoteL2("server1", new URI("/xyz"));
      fail("expected ManagementSourceException");
    } catch (ManagementSourceException mse) {
      assertNotNull(mse.getErrorEntity());
    }

    verify(client).target(eq(new URI("http://server-host1:9540/xyz")));
    verify(builder).post(Mockito.<Entity<Object>>eq(null));
  }

  @Test
  public void testPost2ToRemoteL2_works() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client);
    remoteManagementSource.postToRemoteL2("server1", new URI("/xyz"), (Collection)Collections.singleton("aaa"), String.class);

    verify(client).target(eq(new URI("http://server-host1:9540/xyz")));
    ArgumentCaptor<Entity> argument = ArgumentCaptor.forClass(Entity.class);
    verify(builder).post(argument.capture(), eq(String.class));
    assertEquals(Collections.singleton("aaa"), argument.getValue().getEntity());
  }

  @Test
  public void testPost2ToRemoteL2_fails() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    when(builder.post(any(Entity.class), any(Class.class))).thenThrow(WebApplicationException.class);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client);
    try {
      remoteManagementSource.postToRemoteL2("server1", new URI("/xyz"), (Collection)Collections.singleton("aaa"), String.class);
      fail("expected ManagementSourceException");
    } catch (ManagementSourceException mse) {
      assertNotNull(mse.getErrorEntity());
    }

    verify(client).target(eq(new URI("http://server-host1:9540/xyz")));
    ArgumentCaptor<Entity> argument = ArgumentCaptor.forClass(Entity.class);
    verify(builder).post(argument.capture(), eq(String.class));
    assertEquals(Collections.singleton("aaa"), argument.getValue().getEntity());
  }

  @Test
  public void testPost3ToRemoteL2_works() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client);
    remoteManagementSource.postToRemoteL2("server1", new URI("/xyz"), Collection.class, String.class);

    verify(client).target(eq(new URI("http://server-host1:9540/xyz")));
    verify(builder).post((Entity<?>)eq(null), eq(new SubGenericType<Collection, String>(Collection.class, String.class)));
  }

  @Test
  public void testPost3ToRemoteL2_fails() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    when(builder.post(any(), any(SubGenericType.class))).thenThrow(WebApplicationException.class);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client);
    try {
      remoteManagementSource.postToRemoteL2("server1", new URI("/xyz"), Collection.class, String.class);
      fail("expected ManagementSourceException");
    } catch (ManagementSourceException mse) {
      assertNotNull(mse.getErrorEntity());
    }

    verify(client).target(eq(new URI("http://server-host1:9540/xyz")));
    verify(builder).post((Entity<?>)eq(null), eq(new SubGenericType<Collection, String>(Collection.class, String.class)));
  }

  @Test
  public void testAddTsaEventListener_callsOnEvent() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);
    EventInput eventInput = mock(EventInput.class);
    final AtomicInteger counter = new AtomicInteger();
    when(eventInput.read()).then(new Answer<Object>() {
      @Override
      public Object answer(InvocationOnMock invocation) throws Throwable {
        if (counter.getAndIncrement() < 5) {
          return mock(InboundEvent.class);
        }
        return null;
      }
    });

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client) {
      @Override
      protected long eventReadFailureRetryDelayInMs() {
        return 1L;
      }
    };
    RemoteManagementSource remoteManagementSourceSpy = spy(remoteManagementSource);
    when(remoteManagementSourceSpy.submit(any(Invocation.Builder.class), any(InvocationCallback.class))).thenReturn(mock(Future.class));

    RemoteManagementSource.RemoteTSAEventListener listener = mock(RemoteManagementSource.RemoteTSAEventListener.class);
    remoteManagementSourceSpy.addTsaEventListener(listener);

    ArgumentCaptor<InvocationCallback> callbackArgumentCaptor = ArgumentCaptor.forClass(InvocationCallback.class);
    verify(remoteManagementSourceSpy).submit(any(Invocation.Builder.class), callbackArgumentCaptor.capture());
    InvocationCallback callback = callbackArgumentCaptor.getValue();

    callback.completed(eventInput);

    verify(remoteManagementSourceSpy, times(2)).submit(any(Invocation.Builder.class), any(InvocationCallback.class));
    verify(listener, times(0)).onError(any(Throwable.class));
    verify(listener, times(5)).onEvent(any(InboundEvent.class));
  }

  @Test
  public void testAddTsaEventListener_retryOnException() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client) {
      @Override
      protected long eventReadFailureRetryDelayInMs() {
        return 1L;
      }
    };
    RemoteManagementSource remoteManagementSourceSpy = spy(remoteManagementSource);
    when(remoteManagementSourceSpy.submit(any(Invocation.Builder.class), any(InvocationCallback.class))).thenReturn(mock(Future.class));

    RemoteManagementSource.RemoteTSAEventListener listener = mock(RemoteManagementSource.RemoteTSAEventListener.class);
    remoteManagementSourceSpy.addTsaEventListener(listener);

    ArgumentCaptor<InvocationCallback> callbackArgumentCaptor = ArgumentCaptor.forClass(InvocationCallback.class);
    verify(remoteManagementSourceSpy).submit(any(Invocation.Builder.class), callbackArgumentCaptor.capture());
    InvocationCallback callback = callbackArgumentCaptor.getValue();

    callback.failed(new Exception());

    verify(remoteManagementSourceSpy, times(2)).submit(any(Invocation.Builder.class), any(InvocationCallback.class));
    verify(listener, times(0)).onError(any(Throwable.class));
    verify(listener, times(0)).onEvent(any(InboundEvent.class));
  }

  @Test
  public void testAddTsaEventListener_abortOnWebAppException401() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client) {
      @Override
      protected long eventReadFailureRetryDelayInMs() {
        return 1L;
      }
    };
    RemoteManagementSource remoteManagementSourceSpy = spy(remoteManagementSource);
    when(remoteManagementSourceSpy.submit(any(Invocation.Builder.class), any(InvocationCallback.class))).thenReturn(mock(Future.class));

    RemoteManagementSource.RemoteTSAEventListener listener = mock(RemoteManagementSource.RemoteTSAEventListener.class);
    remoteManagementSourceSpy.addTsaEventListener(listener);

    ArgumentCaptor<InvocationCallback> callbackArgumentCaptor = ArgumentCaptor.forClass(InvocationCallback.class);
    verify(remoteManagementSourceSpy).submit(any(Invocation.Builder.class), callbackArgumentCaptor.capture());
    InvocationCallback callback = callbackArgumentCaptor.getValue();

    callback.failed(new WebApplicationException(401));

    verify(listener, times(1)).onError(any(WebApplicationException.class));
    verify(listener, times(0)).onEvent(any(InboundEvent.class));
  }

  @Test
  public void testAddTsaEventListener_abortOnInterruptedException() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);

    when(localManagementSource.getRemoteServerUrls()).thenReturn(new HashMap<String, String>() {{
      put("server1", "http://server-host1:9540");
    }});

    WebTarget webTarget = mock(WebTarget.class);
    when(webTarget.register(any(Class.class))).thenReturn(webTarget);
    Invocation.Builder builder = mock(Invocation.Builder.class);
    when(webTarget.request()).thenReturn(builder);
    when(builder.header(anyString(), any())).thenReturn(builder);
    when(client.target(any(URI.class))).thenReturn(webTarget);
    AsyncInvoker asyncInvoker = mock(AsyncInvoker.class);
    when(builder.async()).thenReturn(asyncInvoker);
    when(asyncInvoker.get(any(InvocationCallback.class))).thenReturn(mock(Future.class));

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client) {
      @Override
      protected long eventReadFailureRetryDelayInMs() {
        return 1L;
      }
    };
    RemoteManagementSource remoteManagementSourceSpy = spy(remoteManagementSource);
    when(remoteManagementSourceSpy.submit(any(Invocation.Builder.class), any(InvocationCallback.class))).thenReturn(mock(Future.class));

    RemoteManagementSource.RemoteTSAEventListener listener = mock(RemoteManagementSource.RemoteTSAEventListener.class);
    remoteManagementSourceSpy.addTsaEventListener(listener);

    ArgumentCaptor<InvocationCallback> callbackArgumentCaptor = ArgumentCaptor.forClass(InvocationCallback.class);
    verify(remoteManagementSourceSpy).submit(any(Invocation.Builder.class), callbackArgumentCaptor.capture());
    InvocationCallback callback = callbackArgumentCaptor.getValue();

    callback.failed(new InterruptedException());

    verify(listener, times(1)).onError(any(InterruptedException.class));
    verify(listener, times(0)).onEvent(any(InboundEvent.class));
  }

  @Test
  public void testCollectEntitiesFromFutures_futureReturningNullIsNotAddedToResultingCollection() throws Exception {
    LocalManagementSource localManagementSource = mock(LocalManagementSource.class);
    Client client = mock(Client.class);
    Future<MyRepresentable> future = mock(Future.class);

    RemoteManagementSource remoteManagementSource = new RemoteManagementSource(localManagementSource,
        new TimeoutServiceImpl(1000L, defaultConnectionTimeout), executorService, client);

    Collection<MyRepresentable> result = remoteManagementSource.collectEntitiesFromFutures(Collections.singletonMap("server1", future), 1000, "myMethod", 1000);
    assertThat(result.isEmpty(), is(true));
  }

  static class MyRepresentable implements Representable {
    private String agentId;
    @Override
    public String getAgentId() {
      return agentId;
    }
    @Override
    public void setAgentId(String agentId) {
      this.agentId = agentId;
    }
  }


  static class Is implements ArgumentMatcher<AtomicBoolean> {
    @Override
    public boolean matches(AtomicBoolean atomicBoolean) {
      return atomicBoolean.get() == true;
    }

    public static Is atomicTrue() {
      return new Is();
    }
  }

}
