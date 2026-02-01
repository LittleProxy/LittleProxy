package org.littleshoot.proxy;

import static org.littleshoot.proxy.impl.DefaultHttpProxyServer.*;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.core.config.Configurator;
import org.jspecify.annotations.NonNull;
import org.littleshoot.proxy.extras.logging.ActivityLogger;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFieldConfigurationFactory;
import org.littleshoot.proxy.extras.logging.LogFormat;
import org.littleshoot.proxy.extras.logging.StandardField;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;
import org.littleshoot.proxy.impl.ProxyUtils;
import org.littleshoot.proxy.impl.ThreadPoolConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Launches a new HTTP proxy. */
public class Launcher {

  public static final int DEFAULT_PORT = 8080;
  private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

  private static final String OPTION_DNSSEC = "dnssec";

  private static final String OPTION_PORT = "port";

  private static final String OPTION_HELP = "help";

  private static final String OPTION_MITM = "mitm";

  private static final String OPTION_NIC = "nic";

  private static final String OPTION_CONFIG = "config";

  private static final String OPTION_LOG_CONFIG = "log_config";
  private static final String OPTION_SERVER = "server";
  private static final String OPTION_NAME = "name";
  private static final String OPTION_ADDRESS = "address";
  private static final String OPTION_PROXY_ALIAS = "proxy_alias";
  private static final String OPTION_ALLOW_LOCAL_ONLY = "allow_local_only";
  private static final String OPTION_AUTHENTICATE_SSL_CLIENTS = "authenticate_ssl_clients";
  private static final String OPTION_SSL_CLIENTS_TRUST_ALL_SERVERS = SSL_CLIENTS_TRUST_ALL_SERVERS;
  private static final String OPTION_SSL_CLIENTS_SEND_CERTS = SSL_CLIENTS_SEND_CERTS;
  private static final String OPTION_SSL_CLIENTS_KEYSTORE_PATH = SSL_CLIENTS_KEYSTORE_PATH;
  private static final String OPTION_SSL_CLIENTS_KEYSTORE_ALIAS = SSL_CLIENTS_KEYSTORE_ALIAS;
  private static final String OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD = SSL_CLIENTS_KEYSTORE_PASSWORD;
  private static final String OPTION_TRANSPARENT = TRANSPARENT;
  private static final String OPTION_THROTTLE_READ_BYTES_PER_SECOND =
      THROTTLE_READ_BYTES_PER_SECOND;
  private static final String OPTION_THROTTLE_WRITE_BYTES_PER_SECOND =
      THROTTLE_WRITE_BYTES_PER_SECOND;
  private static final String OPTION_ALLOW_REQUEST_TO_ORIGIN_SERVER =
      ALLOW_REQUESTS_TO_ORIGIN_SERVER;
  private static final String OPTION_ALLOW_PROXY_PROTOCOL = ALLOW_PROXY_PROTOCOL;
  private static final String OPTION_SEND_PROXY_PROTOCOL = SEND_PROXY_PROTOCOL;
  private static final String OPTION_CLIENT_TO_PROXY_WORKER_THREADS =
      CLIENT_TO_PROXY_WORKER_THREADS;
  private static final String OPTION_PROXY_TO_SERVER_WORKER_THREADS =
      PROXY_TO_SERVER_WORKER_THREADS;
  private static final String OPTION_ACCEPTOR_THREADS = ACCEPTOR_THREADS;
  private static final String OPTION_ACTIVITY_LOG_FORMAT = "activity_log_format";
  private static final String OPTION_ACTIVITY_LOG_FIELD_CONFIG = "activity_log_field_config";
  private static final String OPTION_ACTIVITY_LOG_PREFIX_HEADERS = "activity_log_prefix_headers";
  private static final String OPTION_ACTIVITY_LOG_REGEX_HEADERS = "activity_log_regex_headers";
  private static final String OPTION_ACTIVITY_LOG_EXCLUDE_HEADERS = "activity_log_exclude_headers";
  private static final String OPTION_ACTIVITY_LOG_MASK_SENSITIVE = "activity_log_mask_sensitive";
  public static final int DELAY_IN_SECONDS_BETWEEN_RELOAD = 15;
  private static final String DEFAULT_JKS_KEYSTORE_PATH = "littleproxy_keystore.jks";

  /**
   * Starts the proxy from the command line.
   *
   * @param args Any command line arguments.
   */
  public static void main(final String... args) {
    Launcher launcher = new Launcher();
    launcher.start(args);
  }

  protected void start(String[] args) {
    final Options options = buildOptions();

    CommandLine cmd = parseCommandLine(args, options);

    configureLogging(cmd);

    LOG.info("Running LittleProxy with args: {}", Arrays.asList(args));

    if (cmd.hasOption(OPTION_HELP)) {
      printHelp(options, null);
      return;
    }

    HttpProxyServerBootstrap bootstrap;
    if (cmd.hasOption(OPTION_CONFIG)) {
      String proxyConfigurationPath = cmd.getOptionValue(OPTION_CONFIG);
      LOG.info("Using configuration file: {}", proxyConfigurationPath);
      cmd.getOptionValue(OPTION_CONFIG);
      bootstrap = bootstrapFromFile(proxyConfigurationPath);
    } else {
      bootstrap = bootstrap();
    }

    int port;
    if (cmd.hasOption(OPTION_PORT)) {
      final String val = cmd.getOptionValue(OPTION_PORT);
      try {
        port = Integer.parseInt(val);
      } catch (final NumberFormatException e) {
        printHelp(options, "Unexpected port " + val);
        return;
      }
    } else {
      port = DEFAULT_PORT;
    }
    bootstrap.withPort(port);
    LOG.info("About to start server on port: '{}'", port);

    if (cmd.hasOption(OPTION_NIC)) {
      final String val = cmd.getOptionValue(OPTION_NIC);
      bootstrap.withNetworkInterface(new InetSocketAddress(val, 0));
    }

    if (cmd.hasOption(OPTION_MITM)) {
      LOG.info("Running as Man in the Middle");
      String keyStorePath = DEFAULT_JKS_KEYSTORE_PATH;
      if (cmd.hasOption(OPTION_SSL_CLIENTS_KEYSTORE_PATH)) {
        keyStorePath = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_PATH);
      }
      bootstrap.withManInTheMiddle(new SelfSignedMitmManager(keyStorePath, true, true));
    }

    if (cmd.hasOption(OPTION_DNSSEC)) {
      final String val = cmd.getOptionValue(OPTION_DNSSEC);
      if (ProxyUtils.isTrue(val)) {
        LOG.info("Using DNSSEC");
        bootstrap.withUseDnsSec(true);
      } else if (ProxyUtils.isFalse(val)) {
        LOG.info("Not using DNSSEC");
        bootstrap.withUseDnsSec(false);
      } else {
        printHelp(options, "Unexpected value for " + OPTION_DNSSEC + "=:" + val);
        return;
      }
    }

    if (cmd.hasOption(OPTION_NAME)) {
      final String val = cmd.getOptionValue(OPTION_NAME);
      LOG.info("Running with name: '{}'", val);
      bootstrap.withName(val);
    }

    if (cmd.hasOption(OPTION_ADDRESS)) {
      final String val = cmd.getOptionValue(OPTION_ADDRESS);
      LOG.info("Binding to address: '{}'", val);
      InetSocketAddress address = ProxyUtils.resolveSocketAddress(val);
      if (address != null) {
        bootstrap.withAddress(address);
      }
    }

    if (cmd.hasOption(OPTION_PROXY_ALIAS)) {
      final String val = cmd.getOptionValue(OPTION_PROXY_ALIAS);
      LOG.info("Using proxy alias: '{}'", val);
      if (val != null) {
        bootstrap.withProxyAlias(val);
      }
    }

    if (cmd.hasOption(OPTION_ALLOW_LOCAL_ONLY)) {
      final String val = cmd.getOptionValue(OPTION_ALLOW_LOCAL_ONLY);
      LOG.info("Setting allow local only to: '{}'", val);
      if (val != null) {
        bootstrap.withAllowLocalOnly(Boolean.parseBoolean(val));
      }
    }

    if (cmd.hasOption(OPTION_AUTHENTICATE_SSL_CLIENTS)) {
      final String val = cmd.getOptionValue(OPTION_AUTHENTICATE_SSL_CLIENTS);
      LOG.info("Setting authenticate SSL clients with a selfSigned cert : '{}'", val);
      if (val != null) {
        boolean trustAllServers =
            Boolean.parseBoolean(cmd.getOptionValue(OPTION_SSL_CLIENTS_TRUST_ALL_SERVERS, "false"));
        boolean sendCerts =
            Boolean.parseBoolean(cmd.getOptionValue(OPTION_SSL_CLIENTS_SEND_CERTS, "false"));
        SelfSignedSslEngineSource sslEngineSource;
        if (cmd.hasOption(OPTION_SSL_CLIENTS_KEYSTORE_PATH)) {
          String keyStorePath = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_PATH);
          if (cmd.hasOption(OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD)) {
            String keyStoreAlias = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_ALIAS, "");
            String keyStorePassword = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD);
            sslEngineSource =
                new SelfSignedSslEngineSource(
                    keyStorePath, trustAllServers, sendCerts, keyStoreAlias, keyStorePassword);
          } else {
            sslEngineSource =
                new SelfSignedSslEngineSource(keyStorePath, trustAllServers, sendCerts);
          }
        } else {
          sslEngineSource =
              new SelfSignedSslEngineSource(DEFAULT_JKS_KEYSTORE_PATH, trustAllServers, sendCerts);
        }
        bootstrap.withSslEngineSource(sslEngineSource);
        bootstrap.withAuthenticateSslClients(Boolean.parseBoolean(val));
      }
    }

    if (cmd.hasOption(OPTION_TRANSPARENT)) {
      String optionValue = cmd.getOptionValue(OPTION_TRANSPARENT);
      LOG.info("Transparent proxy enabled :'{}'", optionValue);
      if (optionValue != null) {
        bootstrap.withTransparent(Boolean.parseBoolean(optionValue));
      }
    }
    long throttlingReadBytesPerSecond = 0;
    long throttlingWriteBytesPerSecond = 0;
    if (cmd.hasOption(OPTION_THROTTLE_READ_BYTES_PER_SECOND)) {
      throttlingReadBytesPerSecond =
          Long.parseLong(cmd.getOptionValue(OPTION_THROTTLE_READ_BYTES_PER_SECOND));
    }
    if (cmd.hasOption(OPTION_THROTTLE_WRITE_BYTES_PER_SECOND)) {
      throttlingWriteBytesPerSecond =
          Long.parseLong(cmd.getOptionValue(OPTION_THROTTLE_WRITE_BYTES_PER_SECOND));
    }
    if (throttlingReadBytesPerSecond > 0 || throttlingWriteBytesPerSecond > 0) {
      LOG.info(
          "Throttling enabled : read {} bytes/s, write {} bytes/s",
          throttlingReadBytesPerSecond,
          throttlingWriteBytesPerSecond);
      bootstrap.withThrottling(throttlingReadBytesPerSecond, throttlingWriteBytesPerSecond);
    }

    if (cmd.hasOption(OPTION_ALLOW_REQUEST_TO_ORIGIN_SERVER)) {
      String optionValue = cmd.getOptionValue(OPTION_ALLOW_REQUEST_TO_ORIGIN_SERVER);
      LOG.info("Allow request to origin server :'{}'", optionValue);
      if (optionValue != null) {
        bootstrap.withAllowRequestToOriginServer(Boolean.parseBoolean(optionValue));
      }
    }

    if (cmd.hasOption(OPTION_ALLOW_PROXY_PROTOCOL)) {
      String optionValue = cmd.getOptionValue(OPTION_ALLOW_PROXY_PROTOCOL);
      LOG.info("Allow proxy protocol :'{}'", optionValue);
      if (optionValue != null) {
        bootstrap.withAcceptProxyProtocol(Boolean.parseBoolean(optionValue));
      }
    }

    if (cmd.hasOption(OPTION_SEND_PROXY_PROTOCOL)) {
      String optionValue = cmd.getOptionValue(OPTION_SEND_PROXY_PROTOCOL);
      LOG.info("Send proxy protocol header:'{}'", optionValue);
      if (optionValue != null) {
        bootstrap.withSendProxyProtocol(Boolean.parseBoolean(optionValue));
      }
    }

    ThreadPoolConfiguration threadPoolConfiguration = new ThreadPoolConfiguration();
    boolean threadPoolConfigSet =
        false; // Flag to track if thread pool configuration is set through command line
    // options
    if (cmd.hasOption(OPTION_CLIENT_TO_PROXY_WORKER_THREADS)) {
      String optionValue = cmd.getOptionValue(OPTION_CLIENT_TO_PROXY_WORKER_THREADS);
      LOG.info("Setting client to proxy worker threads to :'{}'", optionValue);
      if (optionValue != null) {
        threadPoolConfiguration.withClientToProxyWorkerThreads(Integer.parseInt(optionValue));
        threadPoolConfigSet = true;
      }
    }
    if (cmd.hasOption(OPTION_PROXY_TO_SERVER_WORKER_THREADS)) {
      String optionValue = cmd.getOptionValue(OPTION_PROXY_TO_SERVER_WORKER_THREADS);
      LOG.info("Setting proxy to server worker threads to :'{}'", optionValue);
      if (optionValue != null) {
        threadPoolConfiguration.withProxyToServerWorkerThreads(Integer.parseInt(optionValue));
        threadPoolConfigSet = true;
      }
    }
    if (cmd.hasOption(OPTION_ACCEPTOR_THREADS)) {
      String optionValue = cmd.getOptionValue(OPTION_ACCEPTOR_THREADS);
      LOG.info("Setting acceptor threads to :'{}'", optionValue);
      if (optionValue != null) {
        threadPoolConfiguration.withAcceptorThreads(Integer.parseInt(optionValue));
        threadPoolConfigSet = true;
      }
    }
    if (threadPoolConfigSet) {
      bootstrap.withThreadPoolConfiguration(threadPoolConfiguration);
    }

    // Configure activity logging
    configureActivityLogging(cmd, bootstrap, options);

    LOG.info("About to start...");
    HttpProxyServer httpProxyServer = bootstrap.start();
    if (cmd.hasOption(OPTION_SERVER)) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    LOG.info("Shutting down...");
                    httpProxyServer.stop();
                  }));
      try {
        Thread.currentThread().join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Configures activity logging based on CLI options.
   * Supports both file-based configuration and inline CLI options.
   *
   * @param cmd the command line
   * @param bootstrap the server bootstrap
   * @param options the CLI options
   */
  private void configureActivityLogging(CommandLine cmd, HttpProxyServerBootstrap bootstrap, Options options) {
    if (!cmd.hasOption(OPTION_ACTIVITY_LOG_FORMAT)) {
      return;
    }

    String format = cmd.getOptionValue(OPTION_ACTIVITY_LOG_FORMAT);
    LogFormat logFormat;
    try {
      logFormat = LogFormat.valueOf(format.toUpperCase());
    } catch (IllegalArgumentException e) {
      printHelp(options, "Unknown activity log format: " + format);
      return;
    }

    LogFieldConfiguration fieldConfig = null;

    // Try to load configuration from file first
    if (cmd.hasOption(OPTION_ACTIVITY_LOG_FIELD_CONFIG)) {
      String configPath = cmd.getOptionValue(OPTION_ACTIVITY_LOG_FIELD_CONFIG);
      try {
        fieldConfig = LogFieldConfigurationFactory.fromJsonFile(configPath);
        LOG.info("Loaded logging field configuration from: {}", configPath);
      } catch (IOException e) {
        LOG.error("Failed to load logging configuration from {}: {}", configPath, e.getMessage());
        printHelp(options, "Failed to load logging configuration: " + e.getMessage());
        return;
      }
    }

    // If no file config or if CLI options are provided, build/extend configuration
    if (fieldConfig == null || hasCliLoggingOptions(cmd)) {
      LogFieldConfiguration.Builder builder = fieldConfig != null 
          ? LogFieldConfiguration.builder().addAll(fieldConfig.getFields())
          : LogFieldConfiguration.builder();

      // Add standard fields if starting from scratch
      if (fieldConfig == null) {
        builder.addStandardField(StandardField.TIMESTAMP)
               .addStandardField(StandardField.CLIENT_IP)
               .addStandardField(StandardField.METHOD)
               .addStandardField(StandardField.URI)
               .addStandardField(StandardField.STATUS);
      }

      // Process prefix headers
      if (cmd.hasOption(OPTION_ACTIVITY_LOG_PREFIX_HEADERS)) {
        String prefixes = cmd.getOptionValue(OPTION_ACTIVITY_LOG_PREFIX_HEADERS);
        for (String prefix : prefixes.split(",")) {
          prefix = prefix.trim();
          if (!prefix.isEmpty()) {
            builder.addRequestHeadersWithPrefix(prefix);
            LOG.debug("Added prefix header matcher: {}", prefix);
          }
        }
      }

      // Process regex headers
      if (cmd.hasOption(OPTION_ACTIVITY_LOG_REGEX_HEADERS)) {
        String patterns = cmd.getOptionValue(OPTION_ACTIVITY_LOG_REGEX_HEADERS);
        for (String pattern : patterns.split(",")) {
          pattern = pattern.trim();
          if (!pattern.isEmpty()) {
            builder.addRequestHeadersMatching(pattern);
            LOG.debug("Added regex header matcher: {}", pattern);
          }
        }
      }

      // Process exclude headers
      if (cmd.hasOption(OPTION_ACTIVITY_LOG_EXCLUDE_HEADERS)) {
        String patterns = cmd.getOptionValue(OPTION_ACTIVITY_LOG_EXCLUDE_HEADERS);
        for (String pattern : patterns.split(",")) {
          pattern = pattern.trim();
          if (!pattern.isEmpty()) {
            builder.excludeRequestHeadersMatching(pattern);
            LOG.debug("Added exclude header matcher: {}", pattern);
          }
        }
      }

      fieldConfig = builder.build();
    }

    bootstrap.plusActivityTracker(new ActivityLogger(logFormat, fieldConfig));
    LOG.info("Using activity log format: {} with custom field configuration", logFormat);
  }

  /**
   * Checks if any CLI logging options are provided.
   *
   * @param cmd the command line
   * @return true if any logging-related CLI options are present
   */
  private boolean hasCliLoggingOptions(CommandLine cmd) {
    return cmd.hasOption(OPTION_ACTIVITY_LOG_PREFIX_HEADERS)
        || cmd.hasOption(OPTION_ACTIVITY_LOG_REGEX_HEADERS)
        || cmd.hasOption(OPTION_ACTIVITY_LOG_EXCLUDE_HEADERS)
        || cmd.hasOption(OPTION_ACTIVITY_LOG_MASK_SENSITIVE);
  }

  @SuppressWarnings("java:S106")
  private void configureLogging(CommandLine cmd) {
    if (cmd.hasOption(OPTION_LOG_CONFIG)) {
      String optionValue = cmd.getOptionValue(OPTION_LOG_CONFIG);
      File logConfigPath = new File(optionValue);
      if (logConfigPath.exists()) {
        Configurator.initialize(null, logConfigPath.getAbsolutePath());
      }
    } else {
      // default log4j.xml file shipped with the jar
      ClassLoader classLoader = Launcher.class.getClassLoader();
      URL defaultLogConfigUrl = classLoader.getResource("littleproxy_default_log4j2.xml");
      Configurator.initialize(null, defaultLogConfigUrl.toString());
      System.out.println("using 'littleproxy_default_log4j2.xml'");
    }
  }

  private @NonNull CommandLine parseCommandLine(String[] args, Options options) {
    final CommandLineParser parser = new DefaultParser();
    CommandLine cmd;
    try {
      cmd = parser.parse(options, args);
      if (cmd.getArgs().length > 0) {
        throw new UnrecognizedOptionException(
            "Extra arguments were provided in " + Arrays.asList(args));
      }
    } catch (final ParseException e) {
      printHelp(options, "Could not parse command line: " + Arrays.asList(args));
      throw new IllegalArgumentException("Could not parse command line: " + Arrays.asList(args), e);
    }
    return cmd;
  }

  protected @NonNull Options buildOptions() {
    final Options options = new Options();
    options.addOption(null, OPTION_DNSSEC, true, "Request and verify DNSSEC signatures.");
    options.addOption(
        null, OPTION_CONFIG, true, "Path to proxy configuration file (relative or absolute).");
    options.addOption(
        null,
        OPTION_LOG_CONFIG,
        true,
        "Path to log4j configuration file (relative to current directory or absolute).");
    options.addOption(null, OPTION_PORT, true, "Run on the specified port.");
    options.addOption(null, OPTION_NIC, true, "Run on a specified Nic");
    options.addOption(null, OPTION_HELP, false, "Display command line help.");
    options.addOption(null, OPTION_MITM, false, "Run as man in the middle.");
    options.addOption(null, OPTION_SERVER, false, "Run proxy as a server.");
    options.addOption(null, OPTION_NAME, true, "name of the proxy.");
    options.addOption(null, OPTION_ADDRESS, true, "address to bind the proxy.");
    options.addOption(null, OPTION_PROXY_ALIAS, true, "alias for the proxy.");
    options.addOption(
        null,
        OPTION_ALLOW_LOCAL_ONLY,
        true,
        "Allow only local connections to the proxy (true|false).");
    options.addOption(
        null,
        OPTION_AUTHENTICATE_SSL_CLIENTS,
        true,
        "Whether to authenticate SSL clients (true|false).");
    options.addOption(
        null,
        OPTION_SSL_CLIENTS_TRUST_ALL_SERVERS,
        true,
        "Whether SSL clients should trust all servers (true|false).");
    options.addOption(
        null,
        OPTION_SSL_CLIENTS_SEND_CERTS,
        true,
        "Whether SSL clients should send certificates (true|false).");
    options.addOption(
        null, OPTION_SSL_CLIENTS_KEYSTORE_PATH, true, "Path to keystore for SSL clients.");
    options.addOption(
        null, OPTION_SSL_CLIENTS_KEYSTORE_ALIAS, true, "Alias for the keystore for SSL clients.");
    options.addOption(
        null,
        OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD,
        true,
        "Password for the keystore for SSL clients.");
    options.addOption(
        null, OPTION_TRANSPARENT, true, "Whether to run in transparent mode (true|false).");
    options.addOption(
        null, OPTION_THROTTLE_READ_BYTES_PER_SECOND, true, "Throttling read bytes per second.");
    options.addOption(
        null, OPTION_THROTTLE_WRITE_BYTES_PER_SECOND, true, "Throttling write bytes per second.");
    options.addOption(
        null,
        OPTION_ALLOW_REQUEST_TO_ORIGIN_SERVER,
        true,
        "Allow requests to origin server (true|false).");
    options.addOption(
        null, OPTION_ALLOW_PROXY_PROTOCOL, true, "Allow Proxy Protocol (true|false).");
    options.addOption(
        null, OPTION_SEND_PROXY_PROTOCOL, true, "send Proxy Protocol header (true|false).");
    options.addOption(
        null,
        OPTION_CLIENT_TO_PROXY_WORKER_THREADS,
        true,
        "Number of client-to-proxy worker threads.");
    options.addOption(
        null,
        OPTION_PROXY_TO_SERVER_WORKER_THREADS,
        true,
        "Number of proxy-to-server worker threads.");
    options.addOption(null, OPTION_ACCEPTOR_THREADS, true, "Number of acceptor threads.");
    options.addOption(
        null, OPTION_ACTIVITY_LOG_FORMAT, true, "Activity log format: CLF, ELF, JSON, SQUID, W3C, LTSV, CSV, HAPROXY");
    options.addOption(
        null, OPTION_ACTIVITY_LOG_FIELD_CONFIG, true, "Path to JSON configuration file for logging fields");
    options.addOption(
        null, OPTION_ACTIVITY_LOG_PREFIX_HEADERS, true, "Comma-separated list of header prefixes to log (e.g., 'X-Custom-,X-Trace-')");
    options.addOption(
        null, OPTION_ACTIVITY_LOG_REGEX_HEADERS, true, "Comma-separated list of regex patterns for headers to log");
    options.addOption(
        null, OPTION_ACTIVITY_LOG_EXCLUDE_HEADERS, true, "Comma-separated list of regex patterns for headers to exclude from logging");
    options.addOption(
        null, OPTION_ACTIVITY_LOG_MASK_SENSITIVE, true, "Mask sensitive header values (true|false)");
    return options;
  }

  @SuppressWarnings("java:S106")
  private void printHelp(final Options options, final String errorMessage) {
    if (!StringUtils.isBlank(errorMessage)) {
      LOG.error(errorMessage);
      // log4j is not yet loaded at this point in some cases
      System.err.println(errorMessage);
    }

    final HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp("littleproxy", options);
  }
}
