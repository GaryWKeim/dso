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
package com.tc.test.config.builder;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.test.util.TestBaseUtil;

/**
 * @author Ludovic Orban
 */
public class TmsManager {

  private static final Logger LOG = LoggerFactory.getLogger(TmsManager.class);

  private final String warLocation;
  private final int listenPort;
  private final String keystorePath;
  private final String keystorePassword;
  private final boolean ssl;

  private Object server;

  public TmsManager(String warLocation, int listenPort) {
    this(warLocation, listenPort, false, null, null);
  }

  public TmsManager(String warLocation, int listenPort, boolean ssl, String keystorePath, String keystorePassword) {
    this.warLocation = warLocation;
    this.listenPort = listenPort;
    this.keystorePath = keystorePath;
    this.keystorePassword = keystorePassword;
    this.ssl = ssl;
  }

  public void start() throws Exception {
    LOG.info("Deploying TMS on port " + listenPort + " with war archive: " + warLocation);

    URL[] urls = {
        new File(TestBaseUtil.jarFor(Class.forName("org.apache.taglibs.standard.lang.jstl.VariableResolver"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("jakarta.servlet.jsp.jstl.core.ConditionalTagSupport"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("org.apache.el.ExpressionFactoryImpl"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("jakarta.el.ELException"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("jakarta.servlet.ServletContext"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("jakarta.servlet.jsp.JspApplicationContext"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("org.eclipse.jetty.ee10.jsp.JettyJspServlet"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("org.apache.xerces.jaxp.SAXParserFactoryImpl"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("org.eclipse.jetty.server.ServerConnector"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("org.eclipse.jetty.ee10.webapp.WebAppContext"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("org.eclipse.jetty.util.Attributes"))).toURI().toURL(),
        // new File(TestBaseUtil.jarFor(Class.forName("org.objectweb.asm.ClassVisitor"))).toURI().toURL(),
        new File(TestBaseUtil.jarFor(Class.forName("org.eclipse.jetty.server.Server"))).toURI().toURL()
    };

    ClassLoader classloader = new URLClassLoader(urls, null);
    ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(classloader);

    Class<?> serverClazz = classloader.loadClass("org.eclipse.jetty.server.Server");
    Class<?> handlerClazz = classloader.loadClass("org.eclipse.jetty.server.Handler");
    Class<?> webAppContextClazz = classloader.loadClass("org.eclipse.jetty.ee10.webapp.WebAppContext");

    Object webAppContext = webAppContextClazz.newInstance();
    webAppContextClazz.getMethod("setContextPath", String.class).invoke(webAppContext, "/tmc");
    webAppContextClazz.getMethod("setWar", String.class).invoke(webAppContext, warLocation);

    if (!ssl) {
      server = serverClazz.getConstructor(int.class).newInstance(listenPort);
    } else {
      server = serverClazz.getConstructor().newInstance();

      Class<?> contextFactoryClazz = classloader.loadClass("org.eclipse.jetty.server.ConnectionFactory");
      Class<?> sslContextFactoryClazz = classloader.loadClass("org.eclipse.jetty.util.ssl.SslContextFactory");
      Class<?> connectorClazz = classloader.loadClass("org.eclipse.jetty.server.Connector");
      Class<?> serverConnectorClazz = classloader.loadClass("org.eclipse.jetty.server.ServerConnector");

      Object sslContextFactory = sslContextFactoryClazz.getConstructor().newInstance();
      sslContextFactoryClazz.getMethod("setKeyStorePath", String.class).invoke(sslContextFactory, keystorePath);
      sslContextFactoryClazz.getMethod("setKeyStorePassword", String.class).invoke(sslContextFactory, keystorePassword);
      sslContextFactoryClazz.getMethod("setCertAlias", String.class).invoke(sslContextFactory, "l2");

      Object serverConnector = serverConnectorClazz.getConstructor(serverClazz, contextFactoryClazz).newInstance(server, sslContextFactory);
      serverConnectorClazz.getMethod("setPort", int.class).invoke(serverConnector, listenPort);

      serverClazz.getMethod("addConnector", connectorClazz).invoke(server, serverConnector);
    }

    serverClazz.getMethod("setHandler", handlerClazz).invoke(server, webAppContext);
    serverClazz.getMethod("start").invoke(server);
    Thread.currentThread().setContextClassLoader(originalContextClassLoader);
  }

  public void stop() throws Exception {
    if (server != null) {
      server.getClass().getMethod("stop").invoke(server);
      server.getClass().getMethod("join").invoke(server);
      server = null;
    }
  }

}
