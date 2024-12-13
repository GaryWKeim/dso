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
package com.tc.cli;

import com.tc.net.TCSocketAddress;
import org.apache.xmlbeans.XmlException;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;

import com.tc.config.Loader;
import com.tc.config.schema.CommonL2Config;
import com.tc.config.schema.setup.ConfigurationSetupException;
import com.tc.config.schema.setup.ConfigurationSetupManagerFactory;
import com.tc.config.schema.setup.FatalIllegalConfigurationChangeHandler;
import com.tc.config.schema.setup.L2ConfigurationSetupManager;
import com.tc.config.schema.setup.StandardConfigurationSetupManagerFactory;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.object.config.schema.L2DSOConfigObject;
import com.terracottatech.config.Server;
import com.terracottatech.config.TcConfigDocument;

import java.io.Console;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;

/**
 * @author tim
 */
public abstract class ManagementToolUtil {
  private static final TCLogger consoleLogger           = CustomerLogging.getConsoleLogger();

  private static final String DEFAULT_HOST = "localhost";
  private static final int DEFAULT_PORT = 9540;
  private static final int DEFAULT_TSA_PORT = 9510;

  private static boolean securityManagerInitDone = false;

  private static boolean isSecured(CommandLineBuilder commandLineBuilder) {
    return commandLineBuilder.hasOption("secured");
  }

  private static boolean isIgnoreUntrustedCerts(CommandLineBuilder commandLineBuilder) {
    return commandLineBuilder.hasOption('k');
  }

  private static String getUsername(CommandLineBuilder commandLineBuilder) {
    return commandLineBuilder.hasOption("u") ? commandLineBuilder.getOptionValue("u") : null;
  }

  private static String getPassword(CommandLineBuilder commandLineBuilder) {
    if (getUsername(commandLineBuilder) != null) {
      if (commandLineBuilder.hasOption('w')) {
        return commandLineBuilder.getOptionValue('w');
      } else {
        final Console console = System.console();
        if (console != null) {
          return new String(console.readPassword("Enter password: ")); // Hu?!
        } else {
          return CommandLineBuilder.readPassword();
        }
      }
    } else {
      return null;
    }
  }

  private static String getServersList(CommandLineBuilder commandLineBuilder) {
    return commandLineBuilder.hasOption("servers") ? commandLineBuilder.getOptionValue("servers") : null;
  }

  private synchronized static void initSecurityManager() {
    try {
      if (!securityManagerInitDone) {
        final Class<?> securityManagerClass = Class.forName("com.tc.net.core.security.TCClientSecurityManager");
        securityManagerClass.getConstructor(boolean.class).newInstance(true);
        securityManagerInitDone = true;
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void addConnectionOptionsTo(CommandLineBuilder commandLineBuilder) {
    addConnectionOptionsTo(commandLineBuilder, false);
  }

  public static void addConnectionOptionsTo(CommandLineBuilder commandLineBuilder, boolean serversShortOpt) {
    commandLineBuilder.addOption("u", "username", true, "username", String.class, false);
    commandLineBuilder.addOption("w", "password", true, "password; prompted if omitted and username is specified", String.class, false);
    commandLineBuilder.addOption(serversShortOpt ? null : "s", "secured", false, "secured by SSL", String.class, false);
    commandLineBuilder.addOption("k", "ignoreSSLCert", false, "ignore untrusted SSL certificate", String.class, false);
    commandLineBuilder.addOption("f", "config", true, "tc-config.xml file; required if servers are not specified", String.class, false);
    commandLineBuilder.addOption("n", "name", true, "optional name of a server when specifying a tc-config.xml", String.class, false);
    commandLineBuilder.addOption(serversShortOpt ? "s" : null, "servers", true, "comma separated list of host:management-port pairs; required if a config file is not specified", String.class, false);
  }

  public static Collection<WebTarget> getTargets(CommandLineBuilder commandLineBuilder)
      throws ConfigurationSetupException, KeyManagementException, NoSuchAlgorithmException, IOException, XmlException {
    return getTargets(commandLineBuilder, false);
  }

  public static Collection<WebTarget> getTargets(CommandLineBuilder commandLineBuilder, final boolean allFromConfigs)
      throws ConfigurationSetupException, KeyManagementException, NoSuchAlgorithmException, IOException, XmlException {
    commandLineBuilder.parse();

    Collection<WebTarget> targets = new ArrayList<WebTarget>();

    String username = getUsername(commandLineBuilder);
    String password = getPassword(commandLineBuilder);
    boolean secured = isSecured(commandLineBuilder);
    boolean ignoreUntrusted = isIgnoreUntrustedCerts(commandLineBuilder);

    if (commandLineBuilder.hasOption('f') || commandLineBuilder.hasOption('n')) {
      if (allFromConfigs) {
        targets.addAll(getAllTargetsForConfig(commandLineBuilder.getOptionValue("f"), username, password, secured, ignoreUntrusted));
      } else {
        targets.add(getTargetsForConfig(commandLineBuilder.getOptionValue("f"), commandLineBuilder.getOptionValue("n"),
            username, password, secured, ignoreUntrusted));
      }
    }

    if (getServersList(commandLineBuilder) != null) {
      targets.addAll(getTargetsForServersList(getServersList(commandLineBuilder), username, password, secured, ignoreUntrusted));
    }

    for (String leftoverArg : commandLineBuilder.getArguments()) {
      targets.addAll(getTargetsForServersList(leftoverArg, username, password, secured, ignoreUntrusted));
    }

    // if the user hasn't specified any of the methods above, just try the default
    if (targets.isEmpty()) {
      targets.add(targetFor(DEFAULT_HOST, DEFAULT_PORT, username, password, secured, ignoreUntrusted));
    }

    return targets;
  }

  private static Collection<WebTarget> getAllTargetsForConfig(final String configPath, String username, String password, boolean secured,
                                                  boolean ignoreUntrusted) throws IOException, XmlException, NoSuchAlgorithmException, KeyManagementException {
    FileInputStream fis = new FileInputStream(configPath);
    try {
      Collection<WebTarget> targets = new ArrayList<WebTarget>();
      TcConfigDocument tcConfigDocument = new Loader().parse(fis);
      Server[] servers = L2DSOConfigObject.getServers(tcConfigDocument.getTcConfig().getServers());
      for (Server server : servers) {
        targets.add(targetFor(computeHost(server), computeManagementPort(server), username, password, secured, ignoreUntrusted));
      }
      return targets;
    } finally {
      fis.close();
    }
  }

  private static String computeHost(Server serverConfig) {
    String host = serverConfig.getManagementPort() != null ? serverConfig.getManagementPort().getBind() : null;
    if (host == null || host.equals(TCSocketAddress.WILDCARD_IP) || host.equals(TCSocketAddress.WILDCARD_IPv6)) {
      host = serverConfig.getHost();
    }
    if (host == null) host = DEFAULT_HOST;
    return host;
  }

  private static int computeManagementPort(Server l2Config) {
    if (l2Config.isSetManagementPort()) {
      return l2Config.getManagementPort().getIntValue() == 0 ? DEFAULT_PORT : l2Config.getManagementPort().getIntValue();
    } else {
      int tsaPort = DEFAULT_TSA_PORT;
      if (l2Config.isSetTsaPort()) {
        tsaPort = l2Config.getTsaPort().getIntValue();
      }
      return L2DSOConfigObject.computeManagementPortFromTSAPort(tsaPort);
    }
  }

  private static WebTarget getTargetsForConfig(String file, String serverName, String username, String password, boolean secured,
                                                           boolean ignoreUntrusted)
      throws ConfigurationSetupException, NoSuchAlgorithmException, KeyManagementException {
    ArrayList<String> args = new ArrayList<String>();
    if (file != null) {
      args.add("-f");
      args.add(file);
    }
    if (serverName != null) {
      args.add("-n");
      args.add(serverName);
    }
    ConfigurationSetupManagerFactory factory = new StandardConfigurationSetupManagerFactory(
                                                                                            args.toArray(new String[0]),
        StandardConfigurationSetupManagerFactory.ConfigMode.L2, new FatalIllegalConfigurationChangeHandler(), null);
    L2ConfigurationSetupManager l2TVSConfigurationSetupManager = factory.createL2TVSConfigurationSetupManager(null, false);

    String host = computeHost(l2TVSConfigurationSetupManager.commonl2Config());
    int port = computeManagementPort(l2TVSConfigurationSetupManager.commonl2Config());
    if (!secured && l2TVSConfigurationSetupManager.isSecure()) {
      secured = true;
    }
    return targetFor(host, port, username, password, secured, ignoreUntrusted);
  }

  private static String computeHost(CommonL2Config serverConfig) {
    String host = serverConfig.managementPort().getBind();
    if (host == null || host.equals(TCSocketAddress.WILDCARD_IP) || host.equals(TCSocketAddress.WILDCARD_IPv6)) {
      host = serverConfig.host();
    }
    if (host == null) host = DEFAULT_HOST;
    return host;
  }

  private static Collection<WebTarget> getTargetsForServersList(String serversList, String username, String password,
                                                                boolean secured, boolean ignoreUntrusted)
      throws NoSuchAlgorithmException, KeyManagementException {
    return getTargetsForServersList(serversList.split(","), username, password, secured, ignoreUntrusted);
  }

  private static Collection<WebTarget> getTargetsForServersList(String[] serversList, String username, String password,
                                                                boolean secured, boolean ignoreUntrusted)
      throws NoSuchAlgorithmException, KeyManagementException {
    Collection<WebTarget> targets = new ArrayList<WebTarget>();
    for (String hostAndPort : serversList) {
      hostAndPort = hostAndPort.trim();
      int lastColonIndex = hostAndPort.lastIndexOf(":");
      String hostPart = lastColonIndex != -1 ? hostAndPort.substring(0, lastColonIndex) : hostAndPort;
      String portPart = lastColonIndex != -1 ? hostAndPort.substring(lastColonIndex + 1) : null;
      String host = hostPart.trim();
      if (host.startsWith("[")) {
        host = host.substring(1, host.lastIndexOf("]"));
      }
      int port = DEFAULT_PORT;
      if (portPart != null) {
        port = Integer.parseInt(portPart.trim());
      }

      targets.add(targetFor(host, port, username, password, secured, ignoreUntrusted));
    }
    return targets;
  }

  private static int computeManagementPort(CommonL2Config l2Config) {

    if (l2Config.managementPort() != null) {
      return l2Config.managementPort().getIntValue() == 0 ? DEFAULT_PORT : l2Config.managementPort().getIntValue();
    } else {
      int tsaPort = DEFAULT_TSA_PORT;
      if (l2Config.tsaPort() != null) {
        tsaPort = l2Config.tsaPort().getIntValue();
      }
      return L2DSOConfigObject.computeManagementPortFromTSAPort(tsaPort);
    }
  }

  public static WebTarget targetFor(String host, int port, String username, String password, boolean secured,
                                    boolean ignoreUntrusted) throws KeyManagementException, NoSuchAlgorithmException {
    String prefix = secured ? "https" : "http";
    String urlAsString = prefix + "://" + (host.contains(":") ? ("[" + host + "]") : host) + ":" + port;

    if (secured) {
      initSecurityManager();
    }

    ClientBuilder clientBuilder = ClientBuilder.newBuilder();
    if (username != null && password != null) {
      clientBuilder.register(HttpAuthenticationFeature.basic(username, password));
    }
    if (ignoreUntrusted) {
      setupIgnoreUntrustedCerts(clientBuilder);
    }
    Client client = clientBuilder.build();
    return client.target(urlAsString);
  }

  private static void setupIgnoreUntrustedCerts(ClientBuilder builder) throws NoSuchAlgorithmException, KeyManagementException {
    // disable SSL certificate verification

    System.setProperty("tc.ssl.trustAllCerts", "true");
    System.setProperty("tc.ssl.disableHostnameVerifier", "true");

    TrustManager[] trustAllCerts = new TrustManager[] {
        new X509TrustManager() {
          @Override
          public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return null;
          }

          @Override
          public void checkClientTrusted(X509Certificate[] certs, String authType) {
          }

          @Override
          public void checkServerTrusted(X509Certificate[] certs, String authType) {
          }
        }
    };

    SSLContext sc = SSLContext.getInstance("TLS");
    sc.init(null, trustAllCerts, null);
    builder.sslContext(sc);
    builder.hostnameVerifier(new HostnameVerifier() {
      @Override
      public boolean verify(String hostname, SSLSession session) {
        return true;
      }
    });
  }
}
