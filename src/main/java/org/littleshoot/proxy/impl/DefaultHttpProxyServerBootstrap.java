package org.littleshoot.proxy.impl;

import static java.util.Objects.requireNonNullElseGet;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.DefaultHostResolver;
import org.littleshoot.proxy.DnsSecServerResolver;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFiltersSource;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.HttpProxyServerBootstrap;
import org.littleshoot.proxy.Launcher;
import org.littleshoot.proxy.MitmManager;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.ServerConnectionPoolType;
import org.littleshoot.proxy.SslEngineSource;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.extras.ActivityLogger;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DefaultHttpProxyServerBootstrap implements HttpProxyServerBootstrap {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpProxyServerBootstrap.class);

  private static final String DEFAULT_LITTLE_PROXY_NAME = "LittleProxy";
  private static final int MAX_INITIAL_LINE_LENGTH_DEFAULT = 8192;
  private static final int MAX_HEADER_SIZE_DEFAULT = 8192 * 2;
  private static final int MAX_CHUNK_SIZE_DEFAULT = 8192 * 2;
  private static final String DEFAULT_JKS_KEYSTORE_PATH = "littleproxy_keystore.jks";

  private String name = DEFAULT_LITTLE_PROXY_NAME;
  @Nullable private ServerGroup serverGroup;
  private TransportProtocol transportProtocol = TransportProtocol.TCP;
  @Nullable private InetSocketAddress requestedAddress;
  private int port = DefaultHttpProxyServer.DEFAULT_PORT;
  private boolean allowLocalOnly = true;
  @Nullable private SslEngineSource sslEngineSource;
  private boolean authenticateSslClients = true;
  @Nullable private ProxyAuthenticator proxyAuthenticator;
  @Nullable private ChainedProxyManager chainProxyManager;
  @Nullable private MitmManager mitmManager;
  private HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter();
  private boolean transparent;
  private Duration idleConnectionTimeout = Duration.ofSeconds(70);
  private final Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<>();
  private int connectTimeout = 40000;
  private HostResolver serverResolver = new DefaultHostResolver();
  private long readThrottleBytesPerSecond;
  private long writeThrottleBytesPerSecond;
  @Nullable private InetSocketAddress localAddress;
  @Nullable private String proxyAlias;
  private int clientToProxyAcceptorThreads = ServerGroup.DEFAULT_INCOMING_ACCEPTOR_THREADS;
  private int clientToProxyWorkerThreads = ServerGroup.DEFAULT_INCOMING_WORKER_THREADS;
  private int proxyToServerWorkerThreads = ServerGroup.DEFAULT_OUTGOING_WORKER_THREADS;
  private int maxInitialLineLength = MAX_INITIAL_LINE_LENGTH_DEFAULT;
  private int maxHeaderSize = MAX_HEADER_SIZE_DEFAULT;
  private int maxChunkSize = MAX_CHUNK_SIZE_DEFAULT;
  private boolean allowRequestToOriginServer;
  private boolean acceptProxyProtocol;
  private boolean sendProxyProtocol;
  private boolean useSharedServerConnectionPool = false;
  private int maxConnectionsPerHost = 10;
  private int maxConnections = ConcurrentMapServerConnectionPool.DEFAULT_MAX_TOTAL_CONNECTIONS;
  private ServerConnectionPoolType serverConnectionPoolType =
      ServerConnectionPoolType.CONCURRENT_MAP;
  @Nullable private Duration poolIdleTimeout;

  DefaultHttpProxyServerBootstrap() {}

  DefaultHttpProxyServerBootstrap(Properties props) {
    withUseDnsSec(ProxyUtils.extractBooleanDefaultFalse(props, "dnssec"));
    transparent = ProxyUtils.extractBooleanDefaultFalse(props, DefaultHttpProxyServer.TRANSPARENT);
    idleConnectionTimeout =
        Duration.ofSeconds(ProxyUtils.extractInt(props, "idle_connection_timeout"));
    connectTimeout = ProxyUtils.extractInt(props, "connect_timeout", 0);
    maxInitialLineLength =
        ProxyUtils.extractInt(props, "max_initial_line_length", MAX_INITIAL_LINE_LENGTH_DEFAULT);
    maxHeaderSize = ProxyUtils.extractInt(props, "max_header_size", MAX_HEADER_SIZE_DEFAULT);
    maxChunkSize = ProxyUtils.extractInt(props, "max_chunk_size", MAX_CHUNK_SIZE_DEFAULT);
    if (props.containsKey(DefaultHttpProxyServer.NAME)) {
      name = props.getProperty(DefaultHttpProxyServer.NAME, DEFAULT_LITTLE_PROXY_NAME);
    }
    if (props.containsKey(DefaultHttpProxyServer.ADDRESS)) {
      requestedAddress =
          ProxyUtils.resolveSocketAddress(props.getProperty(DefaultHttpProxyServer.ADDRESS));
    }
    if (props.containsKey(DefaultHttpProxyServer.PORT)) {
      port = ProxyUtils.extractInt(props, DefaultHttpProxyServer.PORT, Launcher.DEFAULT_PORT);
    }
    if (props.containsKey(DefaultHttpProxyServer.NIC)) {
      localAddress =
          new InetSocketAddress(
              props.getProperty(
                  DefaultHttpProxyServer.NIC, DefaultHttpProxyServer.DEFAULT_NIC_VALUE),
              0);
    }
    if (props.containsKey(DefaultHttpProxyServer.PROXY_ALIAS)) {
      proxyAlias = props.getProperty(DefaultHttpProxyServer.PROXY_ALIAS);
    }
    if (props.containsKey(DefaultHttpProxyServer.ALLOW_LOCAL_ONLY)) {
      allowLocalOnly =
          ProxyUtils.extractBooleanDefaultFalse(props, DefaultHttpProxyServer.ALLOW_LOCAL_ONLY);
    }
    if (props.containsKey(DefaultHttpProxyServer.AUTHENTICATE_SSL_CLIENTS)) {
      authenticateSslClients =
          ProxyUtils.extractBooleanDefaultFalse(
              props, DefaultHttpProxyServer.AUTHENTICATE_SSL_CLIENTS);
      boolean trustAllServers =
          ProxyUtils.extractBooleanDefaultFalse(
              props, DefaultHttpProxyServer.SSL_CLIENTS_TRUST_ALL_SERVERS);
      boolean sendCerts =
          ProxyUtils.extractBooleanDefaultFalse(
              props, DefaultHttpProxyServer.SSL_CLIENTS_SEND_CERTS);

      if (authenticateSslClients
          && props.containsKey(DefaultHttpProxyServer.SSL_CLIENTS_KEYSTORE_PATH)) {
        String keyStorePath = props.getProperty(DefaultHttpProxyServer.SSL_CLIENTS_KEYSTORE_PATH);
        if (props.containsKey(DefaultHttpProxyServer.SSL_CLIENTS_KEYSTORE_PASSWORD)) {
          String keyStoreAlias =
              props.getProperty(DefaultHttpProxyServer.SSL_CLIENTS_KEYSTORE_ALIAS, "");
          String keyStorePassword =
              props.getProperty(DefaultHttpProxyServer.SSL_CLIENTS_KEYSTORE_PASSWORD, "");
          sslEngineSource =
              new SelfSignedSslEngineSource(
                  keyStorePath, trustAllServers, sendCerts, keyStoreAlias, keyStorePassword);
        } else {
          sslEngineSource = new SelfSignedSslEngineSource(keyStorePath, trustAllServers, sendCerts);
        }
      } else {
        sslEngineSource =
            new SelfSignedSslEngineSource(DEFAULT_JKS_KEYSTORE_PATH, trustAllServers, sendCerts);
      }
    }
    if (props.containsKey(DefaultHttpProxyServer.TRANSPARENT)) {
      transparent =
          ProxyUtils.extractBooleanDefaultFalse(props, DefaultHttpProxyServer.TRANSPARENT);
    }

    if (props.containsKey(DefaultHttpProxyServer.THROTTLE_READ_BYTES_PER_SECOND)) {
      readThrottleBytesPerSecond =
          ProxyUtils.extractLong(props, DefaultHttpProxyServer.THROTTLE_READ_BYTES_PER_SECOND, 0L);
    }
    if (props.containsKey(DefaultHttpProxyServer.THROTTLE_WRITE_BYTES_PER_SECOND)) {
      writeThrottleBytesPerSecond =
          ProxyUtils.extractLong(props, DefaultHttpProxyServer.THROTTLE_WRITE_BYTES_PER_SECOND, 0L);
    }

    if (props.containsKey(DefaultHttpProxyServer.ALLOW_REQUESTS_TO_ORIGIN_SERVER)) {
      allowRequestToOriginServer =
          ProxyUtils.extractBooleanDefaultFalse(
              props, DefaultHttpProxyServer.ALLOW_REQUESTS_TO_ORIGIN_SERVER);
    }
    if (props.containsKey(DefaultHttpProxyServer.ALLOW_PROXY_PROTOCOL)) {
      acceptProxyProtocol =
          ProxyUtils.extractBooleanDefaultFalse(props, DefaultHttpProxyServer.ALLOW_PROXY_PROTOCOL);
    }
    if (props.containsKey(DefaultHttpProxyServer.SEND_PROXY_PROTOCOL)) {
      sendProxyProtocol =
          ProxyUtils.extractBooleanDefaultFalse(props, DefaultHttpProxyServer.SEND_PROXY_PROTOCOL);
    }
    if (props.containsKey(DefaultHttpProxyServer.SERVER_CONNECTION_POOL_TYPE)) {
      String poolTypeValue =
          props.getProperty(DefaultHttpProxyServer.SERVER_CONNECTION_POOL_TYPE, "CONCURRENT_MAP");
      try {
        serverConnectionPoolType =
            ServerConnectionPoolType.valueOf(poolTypeValue.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        LOG.warn("Unknown server connection pool type: {}", poolTypeValue);
      }
    }
    if (props.containsKey(DefaultHttpProxyServer.MAX_CONNECTIONS_PER_HOST)) {
      maxConnectionsPerHost =
          ProxyUtils.extractInt(
              props, DefaultHttpProxyServer.MAX_CONNECTIONS_PER_HOST, maxConnectionsPerHost);
    }
    if (props.containsKey(DefaultHttpProxyServer.MAX_TOTAL_CONNECTIONS)) {
      maxConnections =
          ProxyUtils.extractInt(
              props, DefaultHttpProxyServer.MAX_TOTAL_CONNECTIONS, maxConnections);
    }
    if (props.containsKey(DefaultHttpProxyServer.CLIENT_TO_PROXY_WORKER_THREADS)) {
      clientToProxyWorkerThreads =
          ProxyUtils.extractInt(props, DefaultHttpProxyServer.CLIENT_TO_PROXY_WORKER_THREADS, 0);
    }
    if (props.containsKey(DefaultHttpProxyServer.PROXY_TO_SERVER_WORKER_THREADS)) {
      proxyToServerWorkerThreads =
          ProxyUtils.extractInt(props, DefaultHttpProxyServer.PROXY_TO_SERVER_WORKER_THREADS, 0);
    }
    if (props.containsKey(DefaultHttpProxyServer.ACCEPTOR_THREADS)) {
      clientToProxyAcceptorThreads =
          ProxyUtils.extractInt(props, DefaultHttpProxyServer.ACCEPTOR_THREADS, 0);
    }
    if (props.containsKey(DefaultHttpProxyServer.ACTIVITY_LOG_FORMAT)) {
      String format = props.getProperty(DefaultHttpProxyServer.ACTIVITY_LOG_FORMAT);
      try {
        org.littleshoot.proxy.extras.LogFormat logFormat =
            org.littleshoot.proxy.extras.LogFormat.valueOf(format.toUpperCase());
        plusActivityTracker(new ActivityLogger(logFormat));
      } catch (IllegalArgumentException e) {
        LOG.warn("Unknown activity log format requested in properties: {}", format);
      }
    }
  }

  DefaultHttpProxyServerBootstrap(ServerGroup serverGroup, DefaultHttpProxyServerConfig config) {
    this.serverGroup = serverGroup;
    this.transportProtocol = config.getTransportProtocol();
    this.requestedAddress = config.getRequestedAddress();
    this.port = config.getRequestedAddress().getPort();
    this.sslEngineSource = config.getSslEngineSource();
    this.authenticateSslClients = config.isAuthenticateSslClients();
    this.proxyAuthenticator = config.getProxyAuthenticator();
    this.chainProxyManager = config.getChainProxyManager();
    this.mitmManager = config.getMitmManager();
    this.filtersSource = config.getFiltersSource();
    this.transparent = config.isTransparent();
    this.idleConnectionTimeout = config.getIdleConnectionTimeout();
    this.activityTrackers.addAll(config.getActivityTrackers());
    this.connectTimeout = config.getConnectTimeout();
    this.serverResolver = config.getServerResolver();
    this.readThrottleBytesPerSecond = config.getReadThrottleBytesPerSecond();
    this.writeThrottleBytesPerSecond = config.getWriteThrottleBytesPerSecond();
    this.localAddress = config.getLocalAddress();
    this.proxyAlias = config.getProxyAlias();
    this.maxInitialLineLength = config.getMaxInitialLineLength();
    this.maxHeaderSize = config.getMaxHeaderSize();
    this.maxChunkSize = config.getMaxChunkSize();
    this.allowRequestToOriginServer = config.isAllowRequestsToOriginServer();
    this.acceptProxyProtocol = config.isAcceptProxyProtocol();
    this.sendProxyProtocol = config.isSendProxyProtocol();
    ServerConnectionPoolConfig poolConfig = config.getServerConnectionPoolConfig();
    this.useSharedServerConnectionPool = poolConfig.isEnabled();
    this.maxConnectionsPerHost = poolConfig.getMaxConnectionsPerHost();
    this.serverConnectionPoolType = poolConfig.getPoolType();
    this.maxConnections = poolConfig.getMaxConnections();
    this.poolIdleTimeout = poolConfig.getIdleTimeout();
  }

  @Override
  public HttpProxyServerBootstrap withName(String name) {
    this.name = name;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withAddress(InetSocketAddress address) {
    requestedAddress = address;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withPort(int port) {
    requestedAddress = null;
    this.port = port;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withNetworkInterface(InetSocketAddress inetSocketAddress) {
    localAddress = inetSocketAddress;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withProxyAlias(String alias) {
    proxyAlias = alias;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withAllowLocalOnly(boolean allowLocalOnly) {
    this.allowLocalOnly = allowLocalOnly;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withSslEngineSource(SslEngineSource sslEngineSource) {
    this.sslEngineSource = sslEngineSource;
    if (mitmManager != null) {
      LOG.warn(
          "Enabled encrypted inbound connections with man in the middle. "
              + "These are mutually exclusive - man in the middle will be disabled.");
      mitmManager = null;
    }
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withAuthenticateSslClients(boolean authenticateSslClients) {
    this.authenticateSslClients = authenticateSslClients;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withProxyAuthenticator(ProxyAuthenticator proxyAuthenticator) {
    this.proxyAuthenticator = proxyAuthenticator;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withChainProxyManager(ChainedProxyManager chainProxyManager) {
    this.chainProxyManager = chainProxyManager;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withManInTheMiddle(MitmManager mitmManager) {
    this.mitmManager = mitmManager;
    if (sslEngineSource != null) {
      LOG.warn(
          "Enabled man in the middle with encrypted inbound connections. "
              + "These are mutually exclusive - encrypted inbound connections will be disabled.");
      sslEngineSource = null;
    }
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withFiltersSource(HttpFiltersSource filtersSource) {
    this.filtersSource = filtersSource;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withUseDnsSec(boolean useDnsSec) {
    if (useDnsSec) {
      serverResolver = new DnsSecServerResolver();
    } else {
      serverResolver = new DefaultHostResolver();
    }
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withTransparent(boolean transparent) {
    this.transparent = transparent;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withIdleConnectionTimeout(int idleConnectionTimeoutInSeconds) {
    this.idleConnectionTimeout = Duration.ofSeconds(idleConnectionTimeoutInSeconds);
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withIdleConnectionTimeout(Duration idleConnectionTimeout) {
    this.idleConnectionTimeout = idleConnectionTimeout;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withConnectTimeout(int connectTimeout) {
    this.connectTimeout = connectTimeout;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withServerResolver(HostResolver serverResolver) {
    this.serverResolver = serverResolver;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withServerGroup(ServerGroup group) {
    serverGroup = group;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap plusActivityTracker(ActivityTracker activityTracker) {
    activityTrackers.add(activityTracker);
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withThrottling(
      long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
    this.readThrottleBytesPerSecond = readThrottleBytesPerSecond;
    this.writeThrottleBytesPerSecond = writeThrottleBytesPerSecond;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withMaxInitialLineLength(int maxInitialLineLength) {
    this.maxInitialLineLength = maxInitialLineLength;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withMaxHeaderSize(int maxHeaderSize) {
    this.maxHeaderSize = maxHeaderSize;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withMaxChunkSize(int maxChunkSize) {
    this.maxChunkSize = maxChunkSize;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withAllowRequestToOriginServer(
      boolean allowRequestToOriginServer) {
    this.allowRequestToOriginServer = allowRequestToOriginServer;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withAcceptProxyProtocol(boolean acceptProxyProtocol) {
    this.acceptProxyProtocol = acceptProxyProtocol;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withSendProxyProtocol(boolean sendProxyProtocol) {
    this.sendProxyProtocol = sendProxyProtocol;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withServerConnectionPoolType(ServerConnectionPoolType poolType) {
    this.serverConnectionPoolType =
        poolType != null ? poolType : ServerConnectionPoolType.CONCURRENT_MAP;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withSharedServerConnectionPool(
      boolean useSharedServerConnectionPool) {
    this.useSharedServerConnectionPool = useSharedServerConnectionPool;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withMaxConnectionsPerHost(int maxConnectionsPerHost) {
    this.maxConnectionsPerHost = maxConnectionsPerHost;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withMaxConnections(int maxConnections) {
    this.maxConnections = maxConnections;
    return this;
  }

  @Override
  public HttpProxyServerBootstrap withPoolIdleTimeout(Duration idleTimeout) {
    this.poolIdleTimeout = idleTimeout;
    return this;
  }

  @Override
  public HttpProxyServer start() {
    return build().start();
  }

  @Override
  public HttpProxyServerBootstrap withThreadPoolConfiguration(
      ThreadPoolConfiguration configuration) {
    clientToProxyAcceptorThreads = configuration.getAcceptorThreads();
    clientToProxyWorkerThreads = configuration.getClientToProxyWorkerThreads();
    proxyToServerWorkerThreads = configuration.getProxyToServerWorkerThreads();
    return this;
  }

  private DefaultHttpProxyServer build() {
    final ServerGroup selectedServerGroup =
        requireNonNullElseGet(
            this.serverGroup,
            () ->
                new ServerGroup(
                    name,
                    clientToProxyAcceptorThreads,
                    clientToProxyWorkerThreads,
                    proxyToServerWorkerThreads));

    ServerConnectionPoolConfig poolConfig =
        new ServerConnectionPoolConfig()
            .setEnabled(useSharedServerConnectionPool)
            .setPoolType(serverConnectionPoolType)
            .setMaxConnectionsPerHost(maxConnectionsPerHost)
            .setMaxConnections(maxConnections)
            .setIdleTimeout(poolIdleTimeout);

    DefaultHttpProxyServerConfig serverConfig =
        new DefaultHttpProxyServerConfig()
            .setTransportProtocol(transportProtocol)
            .setRequestedAddress(determineListenAddress())
            .setSslEngineSource(sslEngineSource)
            .setAuthenticateSslClients(authenticateSslClients)
            .setProxyAuthenticator(proxyAuthenticator)
            .setChainProxyManager(chainProxyManager)
            .setMitmManager(mitmManager)
            .setFiltersSource(filtersSource)
            .setTransparent(transparent)
            .setIdleConnectionTimeout(idleConnectionTimeout)
            .setActivityTrackers(activityTrackers)
            .setConnectTimeout(connectTimeout)
            .setServerResolver(serverResolver)
            .setReadThrottleBytesPerSecond(readThrottleBytesPerSecond)
            .setWriteThrottleBytesPerSecond(writeThrottleBytesPerSecond)
            .setLocalAddress(localAddress)
            .setProxyAlias(proxyAlias)
            .setMaxInitialLineLength(maxInitialLineLength)
            .setMaxHeaderSize(maxHeaderSize)
            .setMaxChunkSize(maxChunkSize)
            .setAllowRequestsToOriginServer(allowRequestToOriginServer)
            .setAcceptProxyProtocol(acceptProxyProtocol)
            .setSendProxyProtocol(sendProxyProtocol)
            .setServerConnectionPoolConfig(poolConfig);

    return new DefaultHttpProxyServer(selectedServerGroup, serverConfig);
  }

  private InetSocketAddress determineListenAddress() {
    if (requestedAddress != null) {
      return requestedAddress;
    }
    if (allowLocalOnly) {
      return new InetSocketAddress(DefaultHttpProxyServer.LOCAL_ADDRESS, port);
    }
    return new InetSocketAddress(port);
  }
}
