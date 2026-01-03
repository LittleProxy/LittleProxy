package org.littleshoot.proxy;

import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.xml.DOMConfigurator;
import org.jspecify.annotations.NonNull;
import org.littleshoot.proxy.extras.ActivityLogger;
import org.littleshoot.proxy.extras.LogFormat;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;
import org.littleshoot.proxy.impl.ProxyUtils;
import org.littleshoot.proxy.impl.ThreadPoolConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import static org.littleshoot.proxy.impl.DefaultHttpProxyServer.*;

/**
 * Launches a new HTTP proxy.
 */
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
    private static final String OPTION_THROTTLE_READ_BYTES_PER_SECOND = THROTTLE_READ_BYTES_PER_SECOND;
    private static final String OPTION_THROTTLE_WRITE_BYTES_PER_SECOND = THROTTLE_WRITE_BYTES_PER_SECOND;
    private static final String OPTION_ALLOW_REQUEST_TO_ORIGIN_SERVER = ALLOW_REQUESTS_TO_ORIGIN_SERVER;
    private static final String OPTION_ALLOW_PROXY_PROTOCOL = ALLOW_PROXY_PROTOCOL;
    private static final String OPTION_SEND_PROXY_PROTOCOL = SEND_PROXY_PROTOCOL;
    private static final String OPTION_CLIENT_TO_PROXY_WORKER_THREADS = CLIENT_TO_PROXY_WORKER_THREADS;
    private static final String OPTION_PROXY_TO_SERVER_WORKER_THREADS = PROXY_TO_SERVER_WORKER_THREADS;
    private static final String OPTION_ACCEPTOR_THREADS = ACCEPTOR_THREADS;
    private static final String OPTION_ACTIVITY_LOG_FORMAT = "activity_log_format";
    private final ClassLoader classLoader = Launcher.class.getClassLoader();

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
        final Options options = getOptions();

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
            bootstrap.withManInTheMiddle(new SelfSignedMitmManager());
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
                printHelp(options, "Unexpected value for " + OPTION_DNSSEC
                        + "=:" + val);
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
                boolean trustAllServers = Boolean
                        .parseBoolean(cmd.getOptionValue(OPTION_SSL_CLIENTS_TRUST_ALL_SERVERS, "false"));
                boolean sendCerts = Boolean.parseBoolean(cmd.getOptionValue(OPTION_SSL_CLIENTS_SEND_CERTS, "false"));
                SelfSignedSslEngineSource sslEngineSource;
                if (cmd.hasOption(OPTION_SSL_CLIENTS_KEYSTORE_PATH)) {
                    String keyStorePath = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_PATH);
                    if (cmd.hasOption(OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD)) {
                        String keyStoreAlias = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_ALIAS, "");
                        String keyStorePassword = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD);
                        sslEngineSource = new SelfSignedSslEngineSource(keyStorePath, trustAllServers, sendCerts,
                                keyStoreAlias, keyStorePassword);
                    } else {
                        sslEngineSource = new SelfSignedSslEngineSource(keyStorePath, trustAllServers, sendCerts);
                    }
                } else {
                    sslEngineSource = new SelfSignedSslEngineSource(trustAllServers, sendCerts);
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
            throttlingReadBytesPerSecond = Long.parseLong(cmd.getOptionValue(OPTION_THROTTLE_READ_BYTES_PER_SECOND));
        }
        if (cmd.hasOption(OPTION_THROTTLE_WRITE_BYTES_PER_SECOND)) {
            throttlingWriteBytesPerSecond = Long.parseLong(cmd.getOptionValue(OPTION_THROTTLE_WRITE_BYTES_PER_SECOND));
        }
        if (throttlingReadBytesPerSecond > 0 || throttlingWriteBytesPerSecond > 0) {
            LOG.info("Throttling enabled : read {} bytes/s, write {} bytes/s", throttlingReadBytesPerSecond,
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
        boolean threadPoolConfigSet = false; // Flag to track if thread pool configuration is set through command line
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

        if (cmd.hasOption(OPTION_ACTIVITY_LOG_FORMAT)) {
            String format = cmd.getOptionValue(OPTION_ACTIVITY_LOG_FORMAT);
            try {
                LogFormat logFormat = LogFormat
                        .valueOf(format.toUpperCase());
                bootstrap.plusActivityTracker(new ActivityLogger(logFormat));
                LOG.info("Using activity log format: {}", logFormat);
            } catch (IllegalArgumentException e) {
                printHelp(options, "Unknown activity log format: " + format);
                return;
            }
        }

        LOG.info("About to start...");
        HttpProxyServer httpProxyServer = bootstrap.start();
        if (cmd.hasOption(OPTION_SERVER)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
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

    @SuppressWarnings("java:S106")
    private void configureLogging(CommandLine cmd) {
        File logConfigPath;
        if (cmd.hasOption(OPTION_LOG_CONFIG)) {
            String optionValue = cmd.getOptionValue(OPTION_LOG_CONFIG);
            logConfigPath = new File(optionValue);
        } else {
            //default log4j.xml file shipped with the jar
            InputStream inputStream;
            try {
                inputStream = classLoader.getResourceAsStream("default_log4j.xml");
                logConfigPath = File.createTempFile("default_log4j", ".xml");
                assert inputStream != null;
                Files.copy(
                        inputStream,
                        logConfigPath.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                System.out.println("tempfile:" + logConfigPath);
                logConfigPath.deleteOnExit();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("using 'default_log4j.xml'");
        }
        pollLog4JConfigurationFileIfAvailable(logConfigPath);
    }

    private @NonNull CommandLine parseCommandLine(String[] args, Options options) {
        final CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
            if (cmd.getArgs().length > 0) {
                throw new UnrecognizedOptionException(
                        "Extra arguments were provided in "
                                + Arrays.asList(args));
            }
        } catch (final ParseException e) {
            printHelp(options,
                    "Could not parse command line: " + Arrays.asList(args));
            throw new IllegalStateException("Could not parse command line: " + Arrays.asList(args));
        }
        return cmd;
    }

    protected @NonNull Options getOptions() {
        final Options options = new Options();
        options.addOption(null, OPTION_DNSSEC, true,
                "Request and verify DNSSEC signatures.");
        options.addOption(null, OPTION_CONFIG, true, "Path to proxy configuration file (relative or absolute).");
        options.addOption(null, OPTION_LOG_CONFIG, true,
                "Path to log4j configuration file (relative to current directory or absolute).");
        options.addOption(null, OPTION_PORT, true, "Run on the specified port.");
        options.addOption(null, OPTION_NIC, true, "Run on a specified Nic");
        options.addOption(null, OPTION_HELP, false,
                "Display command line help.");
        options.addOption(null, OPTION_MITM, false, "Run as man in the middle.");
        options.addOption(null, OPTION_SERVER, false, "Run proxy as a server.");
        options.addOption(null, OPTION_NAME, true, "name of the proxy.");
        options.addOption(null, OPTION_ADDRESS, true, "address to bind the proxy.");
        options.addOption(null, OPTION_PROXY_ALIAS, true, "alias for the proxy.");
        options.addOption(null, OPTION_ALLOW_LOCAL_ONLY, true,
                "Allow only local connections to the proxy (true|false).");
        options.addOption(null, OPTION_AUTHENTICATE_SSL_CLIENTS, true,
                "Whether to authenticate SSL clients (true|false).");
        options.addOption(null, OPTION_SSL_CLIENTS_TRUST_ALL_SERVERS, true,
                "Whether SSL clients should trust all servers (true|false).");
        options.addOption(null, OPTION_SSL_CLIENTS_SEND_CERTS, true,
                "Whether SSL clients should send certificates (true|false).");
        options.addOption(null, OPTION_SSL_CLIENTS_KEYSTORE_PATH, true, "Path to keystore for SSL clients.");
        options.addOption(null, OPTION_SSL_CLIENTS_KEYSTORE_ALIAS, true, "Alias for the keystore for SSL clients.");
        options.addOption(null, OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD, true,
                "Password for the keystore for SSL clients.");
        options.addOption(null, OPTION_TRANSPARENT, true, "Whether to run in transparent mode (true|false).");
        options.addOption(null, OPTION_THROTTLE_READ_BYTES_PER_SECOND, true, "Throttling read bytes per second.");
        options.addOption(null, OPTION_THROTTLE_WRITE_BYTES_PER_SECOND, true, "Throttling write bytes per second.");
        options.addOption(null, OPTION_ALLOW_REQUEST_TO_ORIGIN_SERVER, true,
                "Allow requests to origin server (true|false).");
        options.addOption(null, OPTION_ALLOW_PROXY_PROTOCOL, true, "Allow Proxy Protocol (true|false).");
        options.addOption(null, OPTION_SEND_PROXY_PROTOCOL, true, "send Proxy Protocol header (true|false).");
        options.addOption(null, OPTION_CLIENT_TO_PROXY_WORKER_THREADS, true,
                "Number of client-to-proxy worker threads.");
        options.addOption(null, OPTION_PROXY_TO_SERVER_WORKER_THREADS, true,
                "Number of proxy-to-server worker threads.");
        options.addOption(null, OPTION_ACCEPTOR_THREADS, true, "Number of acceptor threads.");
        options.addOption(null, OPTION_ACTIVITY_LOG_FORMAT, true, "Activity log format: CLF, ELF, JSON, SQUID, W3C");
        return options;
    }


    @SuppressWarnings("java:S106")
    private void printHelp(final Options options,
                           final String errorMessage) {
        if (!StringUtils.isBlank(errorMessage)) {
            LOG.error(errorMessage);
            //log4j is not yet loaded at this point in some cases
            System.err.println(errorMessage);
        }

        final HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("littleproxy", options);
    }

    private void pollLog4JConfigurationFileIfAvailable(File log4jConfigurationFile) {
        if (log4jConfigurationFile.exists()) {
            DOMConfigurator.configureAndWatch(
                    log4jConfigurationFile.getAbsolutePath(), 15);
        }
    }
}
