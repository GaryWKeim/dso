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
package com.terracotta.management.web.shiro;

import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.terracotta.management.ApplicationTsaService;
import com.terracotta.management.service.RemoteAgentBridgeService;
import com.terracotta.management.service.TimeoutService;
import com.terracotta.management.service.impl.RemoteAgentBridgeServiceImpl;
import com.terracotta.management.service.impl.TimeoutServiceImpl;
import com.terracotta.management.service.impl.util.LocalManagementSource;
import com.terracotta.management.service.impl.util.RemoteManagementSource;
import com.terracotta.management.web.utils.TSAConfig;
import org.apache.shiro.web.env.EnvironmentLoaderListener;
import org.terracotta.management.ServiceLocator;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @param <T>
 * @author Ludovic Orban
 */
public class TSAEnvironmentLoaderListener<T> extends EnvironmentLoaderListener {

  private static final int REJECTION_TIMEOUT = Integer.getInteger("com.tc.management.threadPools.rejectionTimeout", 25);

  private volatile ThreadPoolExecutor l1BridgeExecutorService;
  private volatile ThreadPoolExecutor tsaExecutorService;
  private volatile RemoteManagementSource remoteManagementSource;

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    try {
      ServiceLocator serviceLocator = new ServiceLocator();

      /// TSA services ///

      int maxThreads = TCPropertiesImpl.getProperties().getInt(TCPropertiesConsts.L2_REMOTEJMX_MAXTHREADS);
      l1BridgeExecutorService = new ThreadPoolExecutor(maxThreads, maxThreads, 60L, TimeUnit.SECONDS,
          new ArrayBlockingQueue<Runnable>(maxThreads * 32, true), new ManagementThreadFactory("Management-Agent-L1"));
      l1BridgeExecutorService.allowCoreThreadTimeOut(true);
      l1BridgeExecutorService.setRejectedExecutionHandler(new RejectedExecutionHandler() {
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
          try {
            boolean accepted = executor.getQueue().offer(r, REJECTION_TIMEOUT, TimeUnit.MILLISECONDS);
            if (!accepted) {
              throw new RejectedExecutionException("L1 Management thread pool saturated, job rejected");
            }
          } catch (InterruptedException ie) {
            throw new RejectedExecutionException("L1 Management thread pool interrupted, job rejected", ie);
          }
        }
      });

      tsaExecutorService = new ThreadPoolExecutor(maxThreads, maxThreads, 60L, TimeUnit.SECONDS,
          new ArrayBlockingQueue<Runnable>(maxThreads * 32, true), new ManagementThreadFactory("Management-Agent-L2"));
      tsaExecutorService.allowCoreThreadTimeOut(true);
      tsaExecutorService.setRejectedExecutionHandler(new RejectedExecutionHandler() {
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
          try {
            boolean accepted = executor.getQueue().offer(r, REJECTION_TIMEOUT, TimeUnit.MILLISECONDS);
            if (!accepted) {
              throw new RejectedExecutionException("L2 Management thread pool saturated, job rejected");
            }
          } catch (InterruptedException ie) {
            throw new RejectedExecutionException("L2 Management thread pool interrupted, job rejected", ie);
          }
        }
      });

      TimeoutService timeoutService = new TimeoutServiceImpl(
          TSAConfig.getDefaultL1BridgeTimeout(),
          TSAConfig.getDefaultL1BridgeConnectionTimeout());

      LocalManagementSource localManagementSource = new LocalManagementSource();

      RemoteAgentBridgeService remoteAgentBridgeService = new RemoteAgentBridgeServiceImpl();

      serviceLocator.loadService(TimeoutService.class, timeoutService);

      /// Security Services ///
      SecuritySetup securitySetup = buildSecuritySetup(serviceLocator);

      remoteManagementSource = securitySetup.buildRemoteManagementSource(localManagementSource, timeoutService,
          tsaExecutorService);
      serviceLocator.loadService(RemoteManagementSource.class, remoteManagementSource);

      ServiceLoader<ApplicationTsaService> loaders = ServiceLoader.load(ApplicationTsaService.class);
      for (ApplicationTsaService applicationEhCacheService : loaders) {
        Map<Class<T>, T> serviceClasses = applicationEhCacheService.getServiceClasses(tsaExecutorService,
            timeoutService, localManagementSource, remoteManagementSource, securitySetup.getSecurityContextService(), securitySetup.getRequestTicketMonitor(), securitySetup.getUserService(), securitySetup.getContextService(), remoteAgentBridgeService, l1BridgeExecutorService);
        for (Entry<Class<T>, T> entry : serviceClasses.entrySet()) {
          serviceLocator.loadService(entry.getKey(), entry.getValue());
        }
      }

      ServiceLocator.load(serviceLocator);

      super.contextInitialized(sce);
    } catch (Exception e) {
      throw new RuntimeException("Error initializing TSAEnvironmentLoaderListener", e);
    }
  }

  protected SecuritySetup buildSecuritySetup(ServiceLocator serviceLocator) throws Exception {
    return new SecuritySetup();
  }

  @Override
  protected Class<?> determineWebEnvironmentClass(ServletContext servletContext) {
    return TSAIniWebEnvironment.class;
  }

  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    if (remoteManagementSource != null) {
      remoteManagementSource.shutdown();
    }
    if (l1BridgeExecutorService != null) {
      l1BridgeExecutorService.shutdown();
    }
    if (tsaExecutorService != null) {
      tsaExecutorService.shutdown();
    }

    super.contextDestroyed(sce);
  }

  private static final class ManagementThreadFactory implements ThreadFactory {
    private final AtomicInteger threadNumberGenerator = new AtomicInteger(1);

    private final ThreadGroup group = (System.getSecurityManager() != null) ?
        System.getSecurityManager().getThreadGroup() : Thread.currentThread().getThreadGroup();
    private final String threadNamePrefix;

    private ManagementThreadFactory(String threadNamePrefix) {
      this.threadNamePrefix = threadNamePrefix;
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread thread = new Thread(group, r, threadNamePrefix + "-" + threadNumberGenerator.getAndIncrement(), 0);
      if (thread.isDaemon()) {
        thread.setDaemon(false);
      }
      if (thread.getPriority() != Thread.NORM_PRIORITY) {
        thread.setPriority(Thread.NORM_PRIORITY);
      }
      return thread;
    }
  }

}
