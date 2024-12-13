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
package com.tc.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.helpers.NOPAppender;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.util.FileSize;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;

import com.tc.properties.TCProperties;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.Assert;
import com.tc.util.ProductInfo;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * Factory class for obtaining TCLogger instances.
 * 
 * @author teck
 */
public class TCLogging {

  public static final String        LOG_CONFIGURATION_PREFIX           = "The configuration read for Logging: ";

  private static final int          MAX_BUFFERED_LOG_MESSAGES          = 10 * 1000;

  private static final String[]     INTERNAL_LOGGER_NAMESPACES         = new String[] { "com.tc", "com.terracotta",
      "com.terracottatech", "org.terracotta", "tc.operator"           };

  private static final String       CUSTOMER_LOGGER_NAMESPACE          = "com.terracottatech";
  private static final String       CUSTOMER_LOGGER_NAMESPACE_WITH_DOT = CUSTOMER_LOGGER_NAMESPACE + ".";

  private static final String       CONSOLE_LOGGER_NAME                = CUSTOMER_LOGGER_NAMESPACE + ".console";
  public static final String        DUMP_LOGGER_NAME                   = "com.tc.dumper.dump";
  public static final String        DERBY_LOGGER_NAME                  = "com.tc.derby.log";
  private static final String       OPERATOR_EVENT_LOGGER_NAME         = "tc.operator.event";

  private static final String       LOGGING_PROPERTIES_SECTION         = "logging";
  private static final String       MAX_LOG_FILE_SIZE_PROPERTY         = "maxLogFileSize";
  private static final int          LOG_COLLISION_LIMIT                = 100;
  private static final int          DEFAULT_MAX_LOG_FILE_SIZE          = 512;
  private static final String       MAX_BACKUPS_PROPERTY               = "maxBackups";
  private static final int          DEFAULT_MAX_BACKUPS                = 20;
  private static final String       BACKUP_FILE_SUFFIX_PROPERTY        = "backupFileSuffix";
  private static final String       DEFAULT_BACKUP_FILE_SUFFIX         = "";
  private static final String       LOG4J_CUSTOM_FILENAME              = ".tc.custom.log4j.properties";
  private static final String       LOG4J_DEV_FILENAME                 = ".tc.dev.log4j.properties";
  private static final String       LOGBACK_CUSTOM_FILENAME            = ".tc.custom.logback.xml";
  public static final String        LOGBACK_DEV_FILENAME               = ".tc.dev.logback.xml";

  private static final String       CONSOLE_PATTERN                    = "%d %p - %m%n";
  private static final String       CONSOLE_PATTERN_DEVELOPMENT        = "%d [%t] %p %c - %m%n";
  public static final String        FILE_AND_JMX_PATTERN               = "%d [%t] %p %c - %m%n";

  private static  TCLogger           console;
  private static  TCLogger           operatorEventLogger;
  private static  ConsoleAppender    consoleAppender;
  private static  Logger[]           allLoggers;

  private static DelegatingAppender delegateFileAppender;
  private static DelegatingAppender delegateBufferingAppender;
  private static boolean            buffering;
  private static File               currentLoggingDirectory            = null;
  private static FileLock           currentLoggingDirectoryFileLock    = null;
  private static boolean            lockingDisabled                    = false;

  private static String             loggingProperties;
  private static LoggerContext      loggerContext;

  public static TCLogger getLogger(Class clazz) {
    if (clazz == null) { throw new IllegalArgumentException("Class cannot be null"); }
    return getLogger(clazz.getName());
  }

  public static TCLogger getLogger(String name) {
    if (name == null) { throw new NullPointerException("Logger cannot be null"); }

    boolean allowedName = false;
    for (String namespace : INTERNAL_LOGGER_NAMESPACES) {
      String withDot = namespace + ".";
      if (name.startsWith(withDot)) {
        allowedName = true;
        break;
      }
    }

    if (!allowedName) {
      //
      throw new IllegalArgumentException("Logger name (" + name + ") not in valid namespace: "
                                         + Arrays.asList(INTERNAL_LOGGER_NAMESPACES));
    }

    return new TCLoggerImpl(name);
  }

  /**
   * This method lets you get a logger w/o any name restrictions. FOR TESTS ONLY (ie. not for shipping code)
   */
  public static TCLogger getTestingLogger(String name) {
    if (name == null) { throw new IllegalArgumentException("Name cannot be null"); }
    return new TCLoggerImpl(name);
  }

  /**
   * This method lets you get a logger w/o any name restrictions. FOR TESTS ONLY (ie. not for shipping code)
   */
  public static TCLogger getTestingLogger(Class clazz) {
    if (clazz == null) { throw new IllegalArgumentException("Class cannot be null"); }
    return getTestingLogger(clazz.getName());
  }

  // You want to look at CustomerLogging to get customer facing logger instances
  static TCLogger getCustomerLogger(String name) {
    if (name == null) { throw new IllegalArgumentException("name cannot be null"); }

    name = CUSTOMER_LOGGER_NAMESPACE_WITH_DOT + name;

    if (CONSOLE_LOGGER_NAME.equals(name)) { throw new IllegalArgumentException("Illegal name: " + name); }

    return new TCLoggerImpl(name);
  }

  // this method not public on purpose, use CustomerLogging.getConsoleLogger() instead
  static TCLogger getConsoleLogger() {
    return console;
  }

  static TCLogger getOperatorEventLogger() {
    return operatorEventLogger;
  }

  private static void reportLoggingError(Exception e) {
    reportLoggingError(null, e);
  }

  private static void reportLoggingError(String message, Exception e) {
    String newLine = System.getProperty("line.separator");
    StringBuffer errorMsg = new StringBuffer(newLine);

    if (message != null) {
      errorMsg.append("WARN: ").append(message).append(newLine);
    }

    if (e != null) {
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      errorMsg.append(sw.toString());
    }

    System.err.println(errorMsg.toString());
  }

  private static void checkForOldConfiguration() {
    File[] loggingLocations = new File[] { new File(System.getProperty("user.home"), LOG4J_CUSTOM_FILENAME),
        new File(System.getProperty("user.dir"), LOG4J_CUSTOM_FILENAME),
        new File(System.getProperty("user.home"), LOG4J_DEV_FILENAME),
        new File(System.getProperty("user.dir"), LOG4J_DEV_FILENAME) };
    try {
      Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
      for (File propFile : loggingLocations) {
        if (propFile.isFile() && propFile.canRead()) {
          String path = propFile.getAbsolutePath();
          String resFileName;
          if (path.contains("custom")) {
            resFileName = LOGBACK_CUSTOM_FILENAME;
          } else {
            resFileName = LOGBACK_DEV_FILENAME;
          }
          rootLogger.warn("The Log4J configuration file \"{}\" is no longer used and should be removed." +
                          "It can be replaced by a Logback configuration file named \"{}\".", path, resFileName);
        }
      }
      InputStream stream = ClassLoader.getSystemClassLoader().getResourceAsStream(LOG4J_CUSTOM_FILENAME);
      if (stream != null) {
        rootLogger.warn("The Log4J configuration file \"{}\" is no longer used and should be removed." +
                        "It can be replaced by a Logback configuration file named \"{}\".",
            ClassLoader.getSystemClassLoader().getResource(LOG4J_CUSTOM_FILENAME), LOGBACK_CUSTOM_FILENAME);
        stream.close();
      }
      stream = ClassLoader.getSystemClassLoader().getResourceAsStream(LOG4J_DEV_FILENAME);
      if (stream != null) {
        rootLogger.warn("The Log4J configuration file \"{}\" is no longer used and should be removed." +
                        "It can be replaced by a Logback configuration file named \"{}\".",
            ClassLoader.getSystemClassLoader().getResource(LOG4J_DEV_FILENAME), LOGBACK_DEV_FILENAME);
        stream.close();
      }
    } catch (Exception e) {
    }
  }

  private static boolean developmentConfiguration() {
    try {
      // Specify the order of LEAST importance; last one in wins
      File[] devLoggingLocations = new File[] { new File(System.getProperty("user.home"), LOGBACK_DEV_FILENAME),
          new File(System.getProperty("user.dir"), LOGBACK_DEV_FILENAME) };
      String devLoggingFile = null;
      boolean devLogBackFilePresent = false;
      InputStream stream = ClassLoader.getSystemClassLoader().getResourceAsStream(LOGBACK_DEV_FILENAME);
      if (stream != null) {
        try {
          devLoggingFile = IOUtils.toString(stream, StandardCharsets.UTF_8);
          JoranConfigurator configurator = new JoranConfigurator();
          configurator.setContext(loggerContext);
          configurator.doConfigure(new ByteArrayInputStream(devLoggingFile.getBytes()));
          devLogBackFilePresent = true;
        } finally {
          stream.close();
        }
      } else {
        for (File propFile : devLoggingLocations) {
          if (propFile.isFile() && propFile.canRead()) {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(loggerContext);
            configurator.doConfigure(propFile.getAbsolutePath());
            devLogBackFilePresent = true;
            devLoggingFile = new String(Files.readAllBytes(propFile.toPath()), Charset.forName("UTF-8"));
          }
        }
      }
      if (devLogBackFilePresent) {
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
        loggingProperties = devLoggingFile;
        return true;
      }
    } catch (Exception e) {
      reportLoggingError(e);
    }
    return false;
  }

  private static boolean customConfiguration() {
    try {
      // First one wins:
      List<File> locations = new ArrayList<File>();
      if (System.getProperty("tc.install-root") != null) {
        locations.add(new File(System.getProperty("tc.install-root"), LOGBACK_CUSTOM_FILENAME));
      }
      locations.add(new File(System.getProperty("user.home"), LOGBACK_CUSTOM_FILENAME));
      locations.add(new File(System.getProperty("user.dir"), LOGBACK_CUSTOM_FILENAME));

      for (File propFile : locations) {
        if (propFile.isFile() && propFile.canRead()) {
          JoranConfigurator configurator = new JoranConfigurator();
          configurator.setContext(loggerContext);
          configurator.doConfigure(propFile.getAbsolutePath());
          String customLoggingFile = new String(Files.readAllBytes(propFile.toPath()), Charset.forName("UTF-8"));
          loggingProperties = customLoggingFile;
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      reportLoggingError(e);
      return false;
    }
  }

  /**
   * <strong>FOR TESTS ONLY</strong>. This allows tests to successfully blow away directories containing log files on
   * Windows. This is a bit of a hack, but working around it is otherwise an enormous pain &mdash; tests only fail on
   * Windows, and you must then very carefully go around, figure out exactly why, and then work around it. Use of this
   * method makes everything a great deal simpler.
   */
  public static synchronized void disableLocking() {
    lockingDisabled = true;

    if (currentLoggingDirectoryFileLock != null) {
      try {
        currentLoggingDirectoryFileLock.release();
        currentLoggingDirectoryFileLock.channel().close();
        currentLoggingDirectoryFileLock = null;
      } catch (IOException ioe) {
        throw Assert.failure("Unable to release file lock?", ioe);
      }
    }
  }

  public static void setLogDirectory(File theDirectory, String logName) {
    Assert.assertNotNull(theDirectory);

    if (theDirectory.getName().trim().equalsIgnoreCase("stdout:")
        || theDirectory.getName().trim().equalsIgnoreCase("stderr:")) {
      if (currentLoggingDirectory != null
          && currentLoggingDirectory.getName().trim().equalsIgnoreCase(theDirectory.getName())) {
        // Nothing to do; great!
        return;
      }
      Appender nopAppender = new NOPAppender<>();
      nopAppender.setContext(loggerContext);
      nopAppender.start();
      delegateFileAppender.setDelegate(nopAppender);
      loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(consoleAppender);
      if (buffering) {
        BufferingAppender realBufferingAppender = (BufferingAppender) delegateBufferingAppender
            .setDelegate(nopAppender);
        realBufferingAppender.stopAndSendContentsTo(consoleAppender);
        realBufferingAppender.stop();
        buffering = false;
      }

      boolean stdout = theDirectory.getName().trim().equalsIgnoreCase("stdout:");
      getConsoleLogger().info("All logging information now output to standard " + (stdout ? "output" : "error") + ".");

      return;
    }

    synchronized (TCLogging.class) {
      if (currentLoggingDirectory != null) {
        try {
          if (theDirectory.getCanonicalPath().equals(currentLoggingDirectory.getCanonicalPath())) { return; }
        } catch (IOException ioe) {
          // oh, well -- what can we do? we'll continue on.
        }
      }
    }

    try {
      FileUtils.forceMkdir(theDirectory);
    } catch (IOException ioe) {
      reportLoggingError("We can't create the directory '" + theDirectory.getAbsolutePath()
                         + "' that you specified for your logs.", ioe);
      return;
    }

    if (!theDirectory.canWrite()) {
      // formatting
      reportLoggingError("The log directory, '" + theDirectory.getAbsolutePath() + "', can't be written to.", null);
      return;
    }

    FileLock thisDirectoryLock = null;
    String lockFileName;
    int counter = 0;
    boolean isLockAcquired = false;

    if (!lockingDisabled) {
      while (!isLockAcquired && counter < LOG_COLLISION_LIMIT) {
        if (counter == 0) {
          lockFileName = "." + logName + ".lock";
        } else {
          lockFileName = "." + logName + ".collision-" + counter + ".lock";
        }
        File lockFile = new File(theDirectory, lockFileName);
        try {
          lockFile.createNewFile();
          Assert.eval(lockFile.exists());
          FileChannel channel = new RandomAccessFile(lockFile, "rw").getChannel();
          thisDirectoryLock = channel.tryLock();

          if (thisDirectoryLock == null) {
            // try different lock
            counter++;
            continue;
          }
        } catch (OverlappingFileLockException ofle) {
          // try different lock file name
          counter++;
          continue;
        } catch (IOException ioe) {
          reportLoggingError("We can't lock the file '" + lockFile.getAbsolutePath() + "', to make sure that only one "
                             + "Terracotta process is using this directory for logging. This may be a permission "
                             + "issue, or some unexpected error. Logging will proceed to the console only.", ioe);
          return;
        }
        isLockAcquired = true;
      }
      if (counter >= LOG_COLLISION_LIMIT) {
        reportLoggingError("Too many log stream collisions (" + LOG_COLLISION_LIMIT
            + "). Logging will proceed to the console only.", null);
        return;
      }
    }

    RollingFileAppender newFileAppender;
    
    String logSuffixName = ((counter == 0) ? ".log" : ".collision-" + counter + ".log");
    String logFileName = logName + logSuffixName;
    String logFilePath = new File(theDirectory, logFileName).getAbsolutePath();
    String fileNamePrefix = "";
    String fileNameSuffix = "";
    int index = logFilePath.lastIndexOf(".");
    fileNamePrefix = logFilePath.substring(0, index);
    fileNameSuffix = logFilePath.substring(index);
    synchronized (TCLogging.class) {
      try {
        TCProperties props = TCPropertiesImpl.getProperties().getPropertiesFor(LOGGING_PROPERTIES_SECTION);
        int maxLogFileSize = props.getInt(MAX_LOG_FILE_SIZE_PROPERTY, DEFAULT_MAX_LOG_FILE_SIZE);
        newFileAppender = new RollingFileAppender();
        newFileAppender.setContext(loggerContext);
        newFileAppender.setName("file appender");
        newFileAppender.setFile(logFilePath);
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern(FILE_AND_JMX_PATTERN);
        encoder.start();
        newFileAppender.setEncoder(encoder);

        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setContext(loggerContext);
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(props.getInt(MAX_BACKUPS_PROPERTY, DEFAULT_MAX_BACKUPS));
        String backupFileSuffix = props.getProperty(BACKUP_FILE_SUFFIX_PROPERTY);
        rollingPolicy.setFileNamePattern(fileNamePrefix + ".%i" + fileNameSuffix +
          (backupFileSuffix != null ? backupFileSuffix : DEFAULT_BACKUP_FILE_SUFFIX));
        rollingPolicy.setParent(newFileAppender);
        rollingPolicy.start();
        newFileAppender.setRollingPolicy(rollingPolicy);

        StartupAndSizeBasedTriggeringPolicy triggeringPolicy = new StartupAndSizeBasedTriggeringPolicy();
        triggeringPolicy.setContext(loggerContext);
        triggeringPolicy.setMaxFileSize(FileSize.valueOf(maxLogFileSize + "MB"));
        triggeringPolicy.start();
        newFileAppender.setTriggeringPolicy(triggeringPolicy);
        newFileAppender.start();
        // Note: order of operations is very important here. We start the new appender before we close and remove the
        // old one so that you don't drop any log records.
        Appender oldFileAppender = delegateFileAppender.setDelegate(newFileAppender);

        if (oldFileAppender != null) {
          oldFileAppender.stop();
        }

        if (buffering) {
          Appender nopAppender = new NOPAppender<>();
          nopAppender.setContext(loggerContext);
          nopAppender.start();
          BufferingAppender realBufferingAppender = (BufferingAppender) delegateBufferingAppender
              .setDelegate(nopAppender);
          realBufferingAppender.stopAndSendContentsTo(delegateFileAppender);
          realBufferingAppender.stop();
          buffering = false;
        }

        currentLoggingDirectory = theDirectory;

        if (currentLoggingDirectoryFileLock != null) currentLoggingDirectoryFileLock.release();
        currentLoggingDirectoryFileLock = thisDirectoryLock;
      } catch (IOException ioe) {
        reportLoggingError("We were unable to switch the logging system to log to '" + logFilePath + "'.", ioe);
      }
    }

    getConsoleLogger().info("Log file: '" + logFilePath + "'.");
    writeSystemProperties();
  }

  public static TCLogger getDumpLogger() {
    return new TCLoggerImpl(DUMP_LOGGER_NAME);
  }

  public static TCLogger getDerbyLogger() {
    return new TCLoggerImpl(DERBY_LOGGER_NAME);
  }

  private static void resetRootLogger() {
    loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
    loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).detachAndStopAllAppenders();
  }

  public static LoggerContext getLoggerContext() {
    if (loggerContext == null) {
      loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    }
    return loggerContext;
  }

  static {
    ClassLoader prevLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(TCLogging.class.getClassLoader());

    try {
      LoggerContext loggerContext = getLoggerContext();
      resetRootLogger();
      boolean customLogging = customConfiguration();
      boolean isDev = customLogging ? false : developmentConfiguration();
      checkForOldConfiguration();
      Logger customerLogger = loggerContext.getLogger(CUSTOMER_LOGGER_NAMESPACE);
      Logger consoleLogger = loggerContext.getLogger(CONSOLE_LOGGER_NAME);

      console = new TCLoggerImpl(CONSOLE_LOGGER_NAME);
      operatorEventLogger = new TCLoggerImpl(OPERATOR_EVENT_LOGGER_NAME);

      List<Logger> internalLoggers = new ArrayList<Logger>();
      for (String nameSpace : INTERNAL_LOGGER_NAMESPACES) {
        internalLoggers.add(loggerContext.getLogger(nameSpace));
      }
      /**
       * Don't add consoleLogger to allLoggers because it's a child of customerLogger, so it shouldn't get any appenders.
       * If you DO add consoleLogger here, you'll see duplicate messages in the log file.
       */
      Logger jettyLogger = loggerContext.getLogger("org.eclipse.jetty");
      internalLoggers.add(jettyLogger);
      allLoggers = createAllLoggerList(internalLoggers, customerLogger);
      if (!customLogging) {
        for (Logger internalLogger : internalLoggers) {
          internalLogger.setLevel(Level.INFO);
        }
        jettyLogger.setLevel(Level.WARN);
        customerLogger.setLevel(Level.INFO);
        consoleLogger.setLevel(Level.INFO);
        if (!isDev) {
          // Only the console logger goes to the console (by default)
          PatternLayoutEncoder ple = new PatternLayoutEncoder();
          ple.setContext(loggerContext);
          ple.setPattern(CONSOLE_PATTERN);
          ple.start();
          consoleAppender = new ConsoleAppender();
          consoleAppender.setContext(loggerContext);
          consoleAppender.setEncoder(ple);
          consoleAppender.start();
          consoleLogger.addAppender(consoleAppender);
        } else {
          PatternLayoutEncoder ple = new PatternLayoutEncoder();
          ple.setContext(loggerContext);
          ple.setPattern(CONSOLE_PATTERN_DEVELOPMENT);
          ple.start();
          consoleAppender = new ConsoleAppender();
          consoleAppender.setContext(loggerContext);
          consoleAppender.setEncoder(ple);
          consoleAppender.start();
          // For non-customer environments, send all logging to the console...
          loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(consoleAppender);
        }
      }
      Appender nopAppender = new NOPAppender<>();
      nopAppender.setContext(loggerContext);
      nopAppender.start();
      delegateFileAppender = new DelegatingAppender(nopAppender);
      delegateFileAppender.setContext(loggerContext);
      delegateFileAppender.start();
      addToAllLoggers(delegateFileAppender);

      BufferingAppender realBufferingAppender = new BufferingAppender(MAX_BUFFERED_LOG_MESSAGES);
      realBufferingAppender.setContext(loggerContext);
      realBufferingAppender.start();
      delegateBufferingAppender = new DelegatingAppender(realBufferingAppender);
      delegateBufferingAppender.setContext(loggerContext);
      delegateBufferingAppender.start();
      addToAllLoggers(delegateBufferingAppender);
      buffering = true;
      if (!isDev) {
        CustomerLogging.getGenericCustomerLogger().info("New logging session started.");
      }
      writeVersion();
      writePID();
      writeLoggingConfigurations();
    } catch (Exception e) {
      reportLoggingError(e);
    } finally {
      Thread.currentThread().setContextClassLoader(prevLoader);
    }
  }

  // for test use only!
  public static LogBackAppenderToTCAppender addAppender(String loggerName, TCAppender appender) {
    LogBackAppenderToTCAppender wrappedAppender = new LogBackAppenderToTCAppender(appender);
    LoggerContext context = getLoggerContext();
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern(CONSOLE_PATTERN_DEVELOPMENT);
    encoder.start();
    wrappedAppender.setContext(context);
    wrappedAppender.start();
    new TCLoggerImpl(loggerName).getLogger().addAppender(wrappedAppender);
    return wrappedAppender;
  }

  public static void removeAppender(String loggerName, LogBackAppenderToTCAppender appender) {
    LoggerContext context = getLoggerContext();
    context.getLogger(new TCLoggerImpl(loggerName).getLogger().getName()).detachAppender(appender.getName());
  }

  private static Logger[] createAllLoggerList(List<Logger> internalLoggers, Logger customerLogger) {
    List<Logger> loggers = new ArrayList<Logger>();
    loggers.addAll(internalLoggers);
    loggers.add(customerLogger);
    return loggers.toArray(new Logger[] {});
  }

  public static void addToAllLoggers(Appender appender) {
    for (Logger allLogger : allLoggers)
      allLogger.addAppender(appender);
  }

  private static void writeVersion() {
    ProductInfo info = ProductInfo.getInstance();
    TCLogger consoleLogger = CustomerLogging.getConsoleLogger();

    // Write build info always
    String longProductString = info.toLongString();
    consoleLogger.info(longProductString);

    // Write patch info, if any
    if (info.isPatched()) {
      String longPatchString = info.toLongPatchString();
      consoleLogger.info(longPatchString);
    }

    String versionMessage = info.versionMessage();
    if (!versionMessage.isEmpty()) {
      consoleLogger.info(versionMessage);
    }
  }

  private static void writePID() {
    try {
      String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
      long pid = Long.parseLong(processName.split("@")[0]);
      getLogger(TCLogging.class).info("PID is " + pid);
    } catch (Throwable t) {
      // ignore, not fatal if this doesn't work for some reason
    }
  }

  private static void writeSystemProperties() {
    try {
      Properties properties = System.getProperties();
      int maxKeyLength = 1;

      ArrayList keys = new ArrayList();
      Iterator iter = properties.entrySet().iterator();
      while (iter.hasNext()) {
        Entry entry = (Entry) iter.next();
        Object objKey = entry.getKey();
        Object objValue = entry.getValue();

        // Filter out any bad non-String keys or values in system properties
        if (objKey instanceof String && objValue instanceof String) {
          String key = (String) objKey;
          keys.add(key);
          maxKeyLength = Math.max(maxKeyLength, key.length());
        }
      }

      String inputArguments = null;
      try {
        RuntimeMXBean mxbean = ManagementFactory.getRuntimeMXBean();
        inputArguments = mxbean.getInputArguments().toString();
      } catch (SecurityException se) {
        inputArguments = "unknown";
      }
      String nl = System.getProperty("line.separator");
      StringBuffer data = new StringBuffer();
      data.append("All Java System Properties for this Terracotta instance:");
      data.append(nl);
      data.append("========================================================================");
      data.append(nl);
      data.append("JVM arguments: " + inputArguments);
      data.append(nl);

      String[] sortedKeys = (String[]) keys.toArray(new String[keys.size()]);
      Arrays.sort(sortedKeys);
      for (String key : sortedKeys) {
        data.append(StringUtils.rightPad(key, maxKeyLength));
        data.append(": ");
        data.append(properties.get(key));
        data.append(nl);
      }
      data.append("========================================================================");

      getLogger(TCLogging.class).info(data.toString());
    } catch (Throwable t) {
      // don't let exceptions here be fatal
      t.printStackTrace();
    }
  }

  // This method for use in tests only
  public static void closeFileAppender() {
    if (delegateFileAppender != null) delegateFileAppender.close();
  }

  
  /**
   * This method will print the logging configurations being used by the logger.
   */
  private static void writeLoggingConfigurations() {
    if (loggingProperties != null) {
      getLogger(TCLogging.class).info(LOG_CONFIGURATION_PREFIX + loggingProperties);
    }
  }

}
