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

import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;

import com.tc.cli.CommandLineBuilder;
import com.tc.cli.ManagementToolUtil;
import com.tc.config.schema.setup.StandardConfigurationSetupManagerFactory;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.util.concurrent.ThreadUtil;

import java.io.IOException;
import java.net.ConnectException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;

import static java.lang.String.format;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;

public class TCStop {
  private static final TCLogger consoleLogger = CustomerLogging.getConsoleLogger();

  private static final int MAX_TRIES = 10;
  private static final int TRY_INTERVAL = 1000;

  private static final String FORCE_OPTION_NAME = "force";
  private static final String STOP_IF_ACTIVE_OPTION_NAME = "stop-if-active";
  private static final String STOP_IF_PASSIVE_OPTION_NAME = "stop-if-passive";
  private static final String RESTART_OPTION_NAME = "restart";
  private static final String RESTART_IN_SAFE_MODE_OPTION_NAME = "restart-in-safe-mode";

  public static void main(String[] args) throws Exception {
    CommandLineBuilder commandLineBuilder = getCommandLineBuilder(args);

    if (commandLineBuilder.hasOption('h')) {
      commandLineBuilder.usageAndDie();
    }

    try {
      stop(args);
    } catch (SecurityException se) {
      consoleLogger.error(se.getMessage(), se);
      commandLineBuilder.usageAndDie();
    } catch (Exception e) {
      System.exit(1);
    }
  }

  public static void stop(final String[] args) throws Exception {
    CommandLineBuilder commandLineBuilder = getCommandLineBuilder(args);

    for (WebTarget target : ManagementToolUtil.getTargets(commandLineBuilder)) {
        try {
          restStop(target, commandLineBuilder);
        } catch (SecurityException se) {
          consoleLogger.error(se.getMessage(), se);
          throw se;
        } catch (Exception e) {
          Throwable root = getRootCause(e);
          if (root instanceof ConnectException) {
            consoleLogger.error("Unable to connect to host '" + target.getUri().getHost() + "', port " + target.getUri().getPort()
                                + ". Are you sure there is a Terracotta server instance running there?");
          } else {
            consoleLogger.error("Unexpected error while stopping server", root);
          }
          throw e;
        }
      }
  }

  protected static CommandLineBuilder getCommandLineBuilder(final String[] args) {Options options = StandardConfigurationSetupManagerFactory
    .createOptions(StandardConfigurationSetupManagerFactory.ConfigMode.L2);
    CommandLineBuilder commandLineBuilder = new CommandLineBuilder(TCStop.class.getName(), args);
    Collection collection = options.getOptions();
    Options filteredOptions = new Options();
    for (Object obj : collection) {
      Option option = (Option)obj;
      if (!"safe-mode".equals(option.getLongOpt())) {
        filteredOptions.addOption(option);
      }
    }

    commandLineBuilder.setOptions(filteredOptions);
    ManagementToolUtil.addConnectionOptionsTo(commandLineBuilder);

    commandLineBuilder.addOption(FORCE_OPTION_NAME, FORCE_OPTION_NAME, false, "force", String.class, false);
    Option stopIfActive = commandLineBuilder.createOption(null, STOP_IF_ACTIVE_OPTION_NAME, false, "Stop only if Active", String.class, false);
    Option stopIfPassive = commandLineBuilder.createOption(null, STOP_IF_PASSIVE_OPTION_NAME, false, "Stop only if Passive", String.class, false);
    Option restart = commandLineBuilder.createOption(null, RESTART_OPTION_NAME, false, "Restart the server", String.class, false);
    Option restartInSafeMode = commandLineBuilder.createOption(null, RESTART_IN_SAFE_MODE_OPTION_NAME, false, "Restart the server in Safe Mode", String.class, false);
    commandLineBuilder.addOption("h", "help", String.class, false);

    OptionGroup activePassiveGroup = new OptionGroup();
    activePassiveGroup.setRequired(false);
    activePassiveGroup.addOption(stopIfActive);
    activePassiveGroup.addOption(stopIfPassive);

    OptionGroup restartGroup = new OptionGroup();
    restartGroup.setRequired(false);
    restartGroup.addOption(restart);
    restartGroup.addOption(restartInSafeMode);

    commandLineBuilder.addOptionGroup(activePassiveGroup);
    commandLineBuilder.addOptionGroup(restartGroup);

    commandLineBuilder.parse();
    return commandLineBuilder;
  }

  private static Throwable getRootCause(Throwable e) {
    Throwable t = e;
    while (t != null) {
      e = t;
      t = t.getCause();
    }
    return e;
  }

  private static void restStop(WebTarget target, CommandLineBuilder commandLineBuilder) throws IOException {
    boolean force = commandLineBuilder.hasOption(FORCE_OPTION_NAME);
    boolean stopIfActive = commandLineBuilder.hasOption(STOP_IF_ACTIVE_OPTION_NAME);
    boolean stopIfPassive = commandLineBuilder.hasOption(STOP_IF_PASSIVE_OPTION_NAME);
    boolean restart = commandLineBuilder.hasOption(RESTART_OPTION_NAME);
    boolean restartInSafeMode = commandLineBuilder.hasOption(RESTART_IN_SAFE_MODE_OPTION_NAME);

    restStop(target, force, stopIfActive, stopIfPassive, restart, restartInSafeMode);
  }

  public static void restStop(WebTarget target, boolean force) throws IOException {
    restStop(target, force, false, false, false, false);
  }

  public static void restStop(WebTarget target,
                              boolean force,
                              boolean stopIfActive,
                              boolean stopIfPassive,
                              boolean restart,
                              boolean restartInSafeMode) throws IOException {
    Map<String, Boolean> map = new HashMap<>();
    map.put("forceStop", force);
    map.put("stopIfActive", stopIfActive);
    map.put("stopIfPassive", stopIfPassive);
    map.put("restart", restart);
    map.put("restartInSafeMode", restartInSafeMode);
    Entity<Map<String, Boolean>> stopConfig = Entity.json(map);
    String hostPort = target.getUri().getHost() + ":" + target.getUri().getPort();

    for (int i = 0; i < MAX_TRIES; i++) {
      Response response = null;
      try {
        response = target.path("/tc-management-api/v2/local/shutdown").request(APPLICATION_JSON_TYPE).post(stopConfig);
        if (response.getStatus() >= 200 && response.getStatus() < 300) {
          Boolean success = response.readEntity(Boolean.class);
          if (success) {
            consoleLogger.info("Stop success. Response code " + response.getStatus());
          } else {
            if (stopIfActive) {
              String errorMsg = "Server is not in active state, not stopping the server";
              consoleLogger.warn(errorMsg);
              throw new RuntimeException(errorMsg);
            } else if (stopIfPassive) {
              String errorMsg = "Server is not in passive state, not stopping the server";
              consoleLogger.warn(errorMsg);
              throw new RuntimeException(errorMsg);
            } else {
              throw new AssertionError();
            }
          }
          return;
        } else if (response.getStatus() == 401) {
          throw new IOException("Authentication error while connecting to " + hostPort + ", check username/password and try again.");
        } else if (response.getStatus() == 403) {
          throw new IOException("Insufficient permissions to perform a server shutdown.");
        } else if (response.getStatus() == 404) {
          consoleLogger.warn("Got a 404 while connecting to " + hostPort + ". Management service might not be started " +
            "yet; retrying.");
          ThreadUtil.reallySleep(TRY_INTERVAL);
        } else {
          Map<String, ?> errorResponse = response.readEntity(Map.class);
          throw new IOException(format("Error stopping server %s: %s", hostPort, errorResponse.get("error")));
        }
      } finally {
        try {
          if (response != null) {
            response.close();
          }
        } catch (Exception ignore) {}
      }
    }
    throw new IOException(format("Unable to shutdown server at %s after %d tries", hostPort, MAX_TRIES));
  }

  public static void restStop(String host, int port, String username, String password, boolean force, boolean secured,
                              final boolean ignoreUntrusted)
      throws IOException, KeyManagementException, NoSuchAlgorithmException {
    restStop(host, port, username, password, force, secured, ignoreUntrusted, false, false, false, false);
  }

  public static void restStop(String host, int port, String username, String password, boolean force, boolean secured,
                              boolean ignoreUntrusted, boolean stopIfActive, boolean stopIfPassive,
                              boolean restart, boolean restartInSafeMode)
      throws IOException, KeyManagementException, NoSuchAlgorithmException {
    restStop(ManagementToolUtil.targetFor(host, port, username, password, secured, ignoreUntrusted), force, stopIfActive,
             stopIfPassive, restart, restartInSafeMode);
  }
}
