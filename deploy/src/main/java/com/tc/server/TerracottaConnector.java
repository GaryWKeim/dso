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

import com.tc.util.MultiIOExceptionHandler;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A Jetty connector that is handed sockets from the DSO listen port once they are identified as HTTP requests
 */
public class TerracottaConnector extends LocalConnector {

  private static final Logger LOGGER = LoggerFactory.getLogger(TerracottaConnector.class);
  static final int DEFAULT_IDLE_TIMEOUT_IN_MS = 10000;
  private final ExecutorService executorService;
  private final Consumer<Socket> reclaimer;

  public TerracottaConnector(Server server, HttpConnectionFactory httpConnectionFactory, Consumer<Socket> reclaimer) {
    this(server, httpConnectionFactory, new ThreadPoolExecutor(2, 64, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new ThreadFactory() {
      final AtomicInteger counter = new AtomicInteger();
      @Override
      public Thread newThread(Runnable r) {
        return new Thread(r, "Jetty Connector - " + counter.incrementAndGet());
      }
    }), DEFAULT_IDLE_TIMEOUT_IN_MS, reclaimer);
  }

  TerracottaConnector(Server server, HttpConnectionFactory httpConnectionFactory, int idleTimeoutInMs, Consumer<Socket> reclaimer) {
    this(server, httpConnectionFactory, new ThreadPoolExecutor(2, 64, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new ThreadFactory() {
      final AtomicInteger counter = new AtomicInteger();
      @Override
      public Thread newThread(Runnable r) {
        return new Thread(r, "Jetty Connector - " + counter.incrementAndGet());
      }
    }), idleTimeoutInMs, reclaimer);
  }

  TerracottaConnector(Server server, HttpConnectionFactory httpConnectionFactory, ExecutorService executor, int idleTimeoutInMs, Consumer<Socket> reclaimer) {
    super(server, httpConnectionFactory);
    this.executorService = executor;
    setIdleTimeout(idleTimeoutInMs);
    this.reclaimer = reclaimer;
  }

  @Override
  public CompletableFuture<Void> shutdown() {
    executorService.shutdownNow();
    return super.shutdown();
  }

  public Future<?> handleSocketFromDSO(Socket socket, byte[] data) {
    //LOGGER.info("handleSocketFromDSO data: " + new String(data));

    Consumer<Socket> reclaimer = this.reclaimer;
    if (reclaimer != null) {
      reclaimer.accept(socket);
    }
    AtomicBoolean responseReceived = new AtomicBoolean();
    LocalEndPoint endPoint = this.connect();
    Future<?> reader = spawnReader(socket, data, endPoint, responseReceived);
    return spawnWriter(socket, endPoint, reader, responseReceived);
  }

  Future<?> spawnReader(Socket socket, byte[] data, LocalEndPoint endPoint, AtomicBoolean responseReceived) {
    try {
      return executorService.submit(() -> {
        try {
          endPoint.addInput(ByteBuffer.wrap(data));

          InputStream inputStream = socket.getInputStream();
          while (!responseReceived.get()) {
            byte[] buffer = new byte[256];
            int read = inputStream.read(buffer);
            if (read == -1) {
              break;
            }
            if (!responseReceived.get()) {
              //LOGGER.info("spawnReader addInput: " + new String(data) + new String(buffer, 0, read));
              endPoint.addInput(ByteBuffer.wrap(buffer, 0, read));
            }
          }
          //LOGGER.info("spawnReader got EOF");
        } catch (Exception e) {
          if (!(IOException.class.isAssignableFrom(e.getClass()))) {
            LOGGER.error("Error processing an HTTP request (reader side)", e);
          } else {
            //LOGGER.info("spawnReader", e);
          }
        }
      });
    } catch (RuntimeException re) {
      // the thread pool is too busy, abandon this request
      MultiIOExceptionHandler m = new MultiIOExceptionHandler();
      m.doSafely(endPoint::close);
      m.doSafely(socket::close);
      m.addAsSuppressedTo(re);
      throw re;
    }
  }

  Future<?> spawnWriter(Socket socket, LocalEndPoint endPoint, Future<?> reader, AtomicBoolean responseReceived) {
    try {
      return executorService.submit(() -> {
        try {
          ByteBuffer byteBuffer = endPoint.waitForOutput(getIdleTimeout(), TimeUnit.MILLISECONDS);
          if (byteBuffer != null && byteBuffer.remaining() > 0) {
            responseReceived.set(true);
            try (WritableByteChannel channel = Channels.newChannel(socket.getOutputStream())) {
              while (byteBuffer.hasRemaining()) {
                //LOGGER.info("spawnWriter write: " + new String(byteBuffer.array()));
                channel.write(byteBuffer);
              }
            }
          }
        } catch (Exception e) {
          if (!(IOException.class.isAssignableFrom(e.getClass()))) {
            LOGGER.error("Error processing an HTTP request (writer side)", e);
          } else {
            //LOGGER.info("spawnWriter", e);
          }
        } finally {
          MultiIOExceptionHandler m = new MultiIOExceptionHandler();
          m.doSafely(endPoint::close);
          m.doSafely(socket::close);
        }
      });
    } catch (RuntimeException re) {
      // the thread pool is too busy, abandon this request
      MultiIOExceptionHandler m = new MultiIOExceptionHandler();
      m.doSafely(endPoint::close);
      m.doSafely(socket::close);
      m.addAsSuppressedTo(re);
      throw re;
    }
  }

}
