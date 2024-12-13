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
package com.tc.server;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TerracottaConnectorTest {

  private Server jetty;

  @Before
  public void setUp() throws Exception {
    jetty = new Server();

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
    context.setContextPath("/");
    context.addServlet(VersionServlet.class, "/*");
    jetty.setHandler(context);
  }

  @After
  public void tearDown() throws Exception {
    if (jetty.isStarted()) {
      jetty.stop();
    }
  }


  @Test
  public void testSimpleCase() throws Exception {
    Socket clientSocket = mock(Socket.class);
    ByteArrayInputStream is = new ByteArrayInputStream("GET / HTTP/1.0\r\n\r\n".getBytes());
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    when(clientSocket.getInputStream()).thenReturn(is);
    when(clientSocket.getOutputStream()).thenReturn(os);

    TerracottaConnector terracottaConnector = new TerracottaConnector(jetty, new HttpConnectionFactory(), null);
    jetty.addConnector(terracottaConnector);
    jetty.start();

    Future<?> f = terracottaConnector.handleSocketFromDSO(clientSocket, new byte[0]);
    f.get(5, TimeUnit.SECONDS);

    String output = new String(os.toByteArray());
    assertThat(output, containsString("<html><title>Version Information</title><body><pre>"));
    verify(clientSocket, atLeastOnce()).close();
  }

  @Test
  public void testAbortingClient() throws Exception {
    Socket clientSocket = mock(Socket.class);
    InputStream is = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("aborting");
      }
    };
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    when(clientSocket.getInputStream()).thenReturn(is);
    when(clientSocket.getOutputStream()).thenReturn(os);

    TerracottaConnector terracottaConnector = new TerracottaConnector(jetty, new HttpConnectionFactory(), 3000, null);
    jetty.addConnector(terracottaConnector);
    jetty.start();

    Future<?> f = terracottaConnector.handleSocketFromDSO(clientSocket, new byte[0]);
    f.get(5, TimeUnit.SECONDS);

    verify(clientSocket, atLeastOnce()).close();
  }

  @Test
  public void testTooSlowClient() throws Exception {
    Socket clientSocket = mock(Socket.class);
    ByteArrayInputStream is = new ByteArrayInputStream("GET / HTTP/1.0".getBytes());
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    when(clientSocket.getInputStream()).thenReturn(is);
    when(clientSocket.getOutputStream()).thenReturn(os);

    TerracottaConnector terracottaConnector = new TerracottaConnector(jetty, new HttpConnectionFactory(), 3000, null);
    jetty.addConnector(terracottaConnector);
    jetty.start();

    Future<?> f = terracottaConnector.handleSocketFromDSO(clientSocket, new byte[0]);
    f.get(5, TimeUnit.SECONDS);

    verify(clientSocket, atLeastOnce()).close();
  }

  @Test
  public void testRejectedExecutionExceptionInReadingSide() throws Exception {
    Socket clientSocket = mock(Socket.class);
    ByteArrayInputStream is = new ByteArrayInputStream("GET / HTTP/1.0\r\n\r\n".getBytes());
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    when(clientSocket.getInputStream()).thenReturn(is);
    when(clientSocket.getOutputStream()).thenReturn(os);

    ExecutorService executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    CountDownLatch latch = new CountDownLatch(1);
    executor.submit(() -> {
      try {
        latch.await();
      } catch (InterruptedException ie) {
        throw new RuntimeException(ie);
      }
    });

    TerracottaConnector terracottaConnector = new TerracottaConnector(jetty, new HttpConnectionFactory(), executor, TerracottaConnector.DEFAULT_IDLE_TIMEOUT_IN_MS, null);
    jetty.addConnector(terracottaConnector);
    jetty.start();

    LocalConnector.LocalEndPoint endPoint = terracottaConnector.connect();

    try {
      terracottaConnector.spawnReader(clientSocket, new byte[0], endPoint);
      fail("expected RejectedExecutionException");
    } catch (RejectedExecutionException ree) {
      // expected
    } finally {
      latch.countDown();
      executor.shutdownNow();
    }

    verify(clientSocket, atLeastOnce()).close();
  }

  @Test
  public void testRejectedExecutionExceptionInWritingSide() throws Exception {
    Socket clientSocket = mock(Socket.class);
    ByteArrayInputStream is = new ByteArrayInputStream("GET / HTTP/1.0\r\n\r\n".getBytes());
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    when(clientSocket.getInputStream()).thenReturn(is);
    when(clientSocket.getOutputStream()).thenReturn(os);

    ExecutorService executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    CountDownLatch latch = new CountDownLatch(1);
    executor.submit(() -> {
      try {
        latch.await();
      } catch (InterruptedException ie) {
        throw new RuntimeException(ie);
      }
    });

    TerracottaConnector terracottaConnector = new TerracottaConnector(jetty, new HttpConnectionFactory(), executor, TerracottaConnector.DEFAULT_IDLE_TIMEOUT_IN_MS, null);
    jetty.addConnector(terracottaConnector);
    jetty.start();

    LocalConnector.LocalEndPoint endPoint = terracottaConnector.connect();
    Future<?> reader = terracottaConnector.spawnReader(clientSocket, new byte[0], endPoint);

    try {
      terracottaConnector.spawnWriter(clientSocket, endPoint, reader);
      fail("expected RejectedExecutionException");
    } catch (RejectedExecutionException ree) {
      // expected
    } finally {
      latch.countDown();
      executor.shutdownNow();
    }

    verify(clientSocket, atLeastOnce()).close();
  }
}
