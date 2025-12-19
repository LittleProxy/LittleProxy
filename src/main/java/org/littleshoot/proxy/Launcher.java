package org.littleshoot.proxy;

import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.xml.DOMConfigurator;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.ProxyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Arrays;

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
    private static final String OPTION_SSL_CLIENTS_TRUST_ALL_SERVERS = "ssl_clients_trust_all_servers";
    private static final String OPTION_SSL_CLIENTS_SEND_CERTS = "ssl_clients_send_certs";
    private static final String OPTION_SSL_CLIENTS_KEYSTORE_PATH = "ssl_clients_keystore_path";
    private static final String OPTION_SSL_CLIENTS_KEYSTORE_ALIAS = "ssl_clients_keystore_alias";
    private static final String OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD = "ssl_clients_keystore_password";
    private static final String OPTION_TRANSPARENT = "transparent";
    private static final String OPTION_THROTTLING_READ_BYTES_PER_SECOND = "throttling_read_bytes_per_second";
    private static final String OPTION_THROTTLING_WRITE_BYTES_PER_SECOND = "throttling_write_bytes_per_second";

    /**
     * Starts the proxy from the command line.
     *
     * @param args Any command line arguments.
     */
    public static void main(final String... args) {

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
        options.addOption(null, OPTION_ALLOW_LOCAL_ONLY, true, "Allow only local connections to the proxy (true|false).");
        options.addOption(null, OPTION_AUTHENTICATE_SSL_CLIENTS, true, "Whether to authenticate SSL clients (true|false).");
        options.addOption(null, OPTION_SSL_CLIENTS_TRUST_ALL_SERVERS, true, "Whether SSL clients should trust all servers (true|false).");
        options.addOption(null, OPTION_SSL_CLIENTS_SEND_CERTS, true, "Whether SSL clients should send certificates (true|false).");
        options.addOption(null, OPTION_SSL_CLIENTS_KEYSTORE_PATH, true, "Path to keystore for SSL clients.");
        options.addOption(null, OPTION_SSL_CLIENTS_KEYSTORE_ALIAS, true, "Alias for the keystore for SSL clients.");
        options.addOption(null, OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD, true, "Password for the keystore for SSL clients.");
        options.addOption(null, OPTION_TRANSPARENT, true, "Whether to run in transparent mode (true|false).");
        options.addOption(null, OPTION_THROTTLING_READ_BYTES_PER_SECOND, true, "Throttling read bytes per second.");
        options.addOption(null, OPTION_THROTTLING_WRITE_BYTES_PER_SECOND, true,"Throttling write bytes per second.");

        final CommandLineParser parser = new DefaultParser();
        final CommandLine cmd;
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
            return;
        }

        String logConfigPath = "src/test/resources/log4j.xml";
        if (cmd.hasOption(OPTION_LOG_CONFIG)) {
            logConfigPath = cmd.getOptionValue(OPTION_LOG_CONFIG);
        }
        pollLog4JConfigurationFileIfAvailable(logConfigPath);

        LOG.info("Running LittleProxy with args: {}", Arrays.asList(args));

        if (cmd.hasOption(OPTION_HELP)) {
            printHelp(options, null);
            return;
        }

        String proxyConfigurationPath = "./littleproxy.properties";
        if (cmd.hasOption(OPTION_CONFIG)) {
            proxyConfigurationPath = cmd.getOptionValue(OPTION_CONFIG);
            LOG.info("Using configuration file: {}", proxyConfigurationPath);
            cmd.getOptionValue(OPTION_CONFIG);
        }


        HttpProxyServerBootstrap bootstrap = DefaultHttpProxyServer
                .bootstrapFromFile(proxyConfigurationPath);


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
                boolean trustAllServers = Boolean.parseBoolean(cmd.getOptionValue(OPTION_SSL_CLIENTS_TRUST_ALL_SERVERS, "false"));
                boolean sendCerts = Boolean.parseBoolean(cmd.getOptionValue(OPTION_SSL_CLIENTS_SEND_CERTS, "false"));
                SelfSignedSslEngineSource sslEngineSource;
                if (cmd.hasOption(OPTION_SSL_CLIENTS_KEYSTORE_PATH)) {
                    String keyStorePath = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_PATH);
                    if(cmd.hasOption(OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD)){
                        String keyStoreAlias = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_ALIAS,"");
                        String keyStorePassword = cmd.getOptionValue(OPTION_SSL_CLIENTS_KEYSTORE_PASSWORD);
                        sslEngineSource = new SelfSignedSslEngineSource(keyStorePath, trustAllServers, sendCerts, keyStoreAlias, keyStorePassword);
                    }else{
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
            if(optionValue != null) {
                bootstrap.withTransparent(Boolean.parseBoolean(optionValue));
            }
        }
        long throttlingReadBytesPerSecond = 0;
        long throttlingWriteBytesPerSecond = 0;
        if (cmd.hasOption(OPTION_THROTTLING_READ_BYTES_PER_SECOND)) {
            throttlingReadBytesPerSecond = Long.parseLong(cmd.getOptionValue(OPTION_THROTTLING_READ_BYTES_PER_SECOND));
        }
        if (cmd.hasOption(OPTION_THROTTLING_WRITE_BYTES_PER_SECOND)) {
            throttlingWriteBytesPerSecond = Long.parseLong(cmd.getOptionValue(OPTION_THROTTLING_WRITE_BYTES_PER_SECOND));
        }
        if(throttlingReadBytesPerSecond > 0 || throttlingWriteBytesPerSecond > 0) {
            LOG.info("Throttling enabled : read {} bytes/s, write {} bytes/s", throttlingReadBytesPerSecond, throttlingWriteBytesPerSecond);
            bootstrap.withThrottling(throttlingReadBytesPerSecond, throttlingWriteBytesPerSecond);
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

    private static void printHelp(final Options options,
                                  final String errorMessage) {
        if (!StringUtils.isBlank(errorMessage)) {
            LOG.error(errorMessage);
            System.err.println(errorMessage);
        }

        final HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("littleproxy", options);
    }

    private static void pollLog4JConfigurationFileIfAvailable(String pathname) {
        File log4jConfigurationFile = new File(pathname);
        if (log4jConfigurationFile.exists()) {
            DOMConfigurator.configureAndWatch(
                    log4jConfigurationFile.getAbsolutePath(), 15);
        }
    }
}
