package org.littleshoot.proxy.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Primary implementation of an {@link HttpProxyServer}.
 *
 * <p>{@link DefaultHttpProxyServer} is bootstrapped by calling {@link #bootstrap()} or {@link
 * #bootstrapFromFile(String)}, and then calling {@link
 * org.littleshoot.proxy.impl.DefaultHttpProxyServerBootstrap#start()}. For example:
 *
 * <pre>
 * DefaultHttpProxyServer server = DefaultHttpProxyServer
 *         .bootstrap()
 *         .withPort(8090)
 *         .start();
 * </pre>
 */
public class DefaultHttpProxyServer implements HttpProxyServer {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpProxyServer.class);

  /**
   * The interval in ms at which the GlobalTrafficShapingHandler will run to compute and throttle
   * the proxy-to-server bandwidth.
   */
  private static final long TRAFFIC_SHAPING_CHECK_INTERVAL_MS = 250L;

  private static final int MAX_INITIAL_LINE_LENGTH_DEFAULT = 8192;
  private static final int MAX_HEADER_SIZE_DEFAULT = 8192 * 2;
  private static final int MAX_CHUNK_SIZE_DEFAULT = 8192 * 2;

  /**
   * The proxy alias to use in the Via header if no explicit proxy alias is specified and the
   * hostname of the local machine cannot be resolved.
   */
  private static final String FALLBACK_PROXY_ALIAS = "littleproxy";

  private static final String DEFAULT_LITTLE_PROXY_NAME = "LittleProxy";
  public static final String LOCAL_ADDRESS = "127.0.0.1";
  public static final int DEFAULT_PORT = 8080;
  public static final String DEFAULT_NIC_VALUE = "0.0.0.0";
  public static final String CLIENT_TO_PROXY_WORKER_THREADS = "client_to_proxy_worker_threads";
  public static final String PROXY_TO_SERVER_WORKER_THREADS = "proxy_to_server_worker_threads";
  public static final String ACTIVITY_LOG_FORMAT = "activity_log_format";
  public static final String ACCEPTOR_THREADS = "acceptor_threads";
  public static final String SEND_PROXY_PROTOCOL = "send_proxy_protocol";
  public static final String ALLOW_PROXY_PROTOCOL = "allow_proxy_protocol";
  public static final String SERVER_CONNECTION_POOL_TYPE = "server_connection_pool_type";
  public static final String MAX_TOTAL_CONNECTIONS = "max_total_connections";
  public static final String MAX_CONNECTIONS_PER_HOST = "max_connections_per_host";
  public static final String ALLOW_REQUESTS_TO_ORIGIN_SERVER = "allow_requests_to_origin_server";
  public static final String THROTTLE_WRITE_BYTES_PER_SECOND = "throttle_write_bytes_per_second";
  public static final String THROTTLE_READ_BYTES_PER_SECOND = "throttle_read_bytes_per_second";
  public static final String TRANSPARENT = "transparent";
  public static final String SSL_CLIENTS_KEYSTORE_PATH = "ssl_clients_keystore_path";
  public static final String SSL_CLIENTS_KEYSTORE_PASSWORD = "ssl_clients_keystore_password";
  public static final String SSL_CLIENTS_KEYSTORE_ALIAS = "ssl_clients_keystore_alias";
  public static final String SSL_CLIENTS_SEND_CERTS = "ssl_clients_send_certs";
  public static final String AUTHENTICATE_SSL_CLIENTS = "authenticate_ssl_clients";
  public static final String SSL_CLIENTS_TRUST_ALL_SERVERS = "ssl_clients_trust_all_servers";
  public static final String ALLOW_LOCAL_ONLY = "allow_local_only";
  public static final String PROXY_ALIAS = "proxy_alias";
  public static final String NIC = "nic";
  public static final String PORT = "port";
  public static final String ADDRESS = "address";
  public static final String NAME = "name";
  private static final String DEFAULT_JKS_KEYSTORE_PATH = "littleproxy_keystore.jks";

  /**
   * Our {@link ServerGroup}. Multiple proxy servers can share the same ServerGroup in order to
   * reuse threads and other such resources.
   */
  private final ServerGroup serverGroup;

  private final TransportProtocol transportProtocol;
  /*
   * The address that the server will attempt to bind to.
   */
  private final InetSocketAddress requestedAddress;
  /*
   * The actual address to which the server is bound. May be different from the
   * requestedAddress in some circumstances,
   * for example when the requested port is 0.
   */
  private final InetSocketAddress localAddress;
  private volatile InetSocketAddress boundAddress;
  private final SslEngineSource sslEngineSource;
  private final boolean authenticateSslClients;
  private final ProxyAuthenticator proxyAuthenticator;
  private final ChainedProxyManager chainProxyManager;
  private final MitmManager mitmManager;
  private final HttpFiltersSource filtersSource;
  private final boolean transparent;
  private volatile int connectTimeout;
  private volatile Duration idleConnectionTimeout;
  private final HostResolver serverResolver;
  private volatile GlobalTrafficShapingHandler globalTrafficShapingHandler;
  private final int maxInitialLineLength;
  private final int maxHeaderSize;
  private final int maxChunkSize;
  private final boolean allowRequestsToOriginServer;
  private final boolean acceptProxyProtocol;
  private final boolean sendProxyProtocol;

  /** The alias or pseudonym for this proxy, used when adding the Via header. */
  private final String proxyAlias;

  /**
   * True when the proxy has already been stopped by calling {@link #stop()} or {@link #abort()}.
   */
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  /** Track all ActivityTrackers for tracking proxying activity. */
  private final Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<>();

  /**
   * Keep track of all channels created by this proxy server for later shutdown when the proxy is
   * stopped.
   */
  private final ChannelGroup allChannels =
      new DefaultChannelGroup("HTTP-Proxy-Server", GlobalEventExecutor.INSTANCE, true);

  /**
   * Shared pool of ProxyToServerConnection instances for all ClientToProxyConnection. This
   * addresses the connection explosion issue (GitHub issue #83).
   */
  private volatile ServerConnectionPool serverConnectionPool;

  /**
   * Whether to use the shared server connection pool. Disabled by default for backwards
   * compatibility.
   */
  private final boolean useSharedServerConnectionPool;

  /**
   * Maximum number of connections per host:port when using the shared connection pool. Default is
   * 10.
   */
  private final int maxConnectionsPerHost;

  /** Maximum total connections in the shared pool. */
  private final int maxConnections;

  /** Selected server connection pool implementation. */
  private final ServerConnectionPoolType serverConnectionPoolType;

  /** Configuration for the server connection pool. */
  private final ServerConnectionPoolConfig serverConnectionPoolConfig;

  /**
   * JVM shutdown hook to shut down this proxy server. Declared as a class-level variable to allow
   * removing the shutdown hook when the proxy server is stopped normally.
   */
  private final Thread jvmShutdownHook = new Thread(this::abort, "LittleProxy-JVM-shutdown-hook");

  /** Bootstrap a new {@link DefaultHttpProxyServer} starting from scratch. */
  public static HttpProxyServerBootstrap bootstrap() {
    return new org.littleshoot.proxy.impl.DefaultHttpProxyServerBootstrap();
  }

  /** Bootstrap a new {@link DefaultHttpProxyServer} using defaults from the given file. */
  public static HttpProxyServerBootstrap bootstrapFromFile(String path) {
    final File propsFile = new File(path);
    Properties props = new Properties();

    if (propsFile.isFile()) {
      try (InputStream is = new FileInputStream(propsFile)) {
        props.load(is);
      } catch (final IOException e) {
        LOG.error("Could not load props file", e);
        throw new IllegalArgumentException("Could not load props file." + e.getMessage());
      }
    } else {
      String cause = !propsFile.exists() ? "absent" : "a directory";
      LOG.error("Could not load props file. file is {}", cause);
      throw new IllegalArgumentException("Could not load props file. file is " + (cause));
    }

    return new org.littleshoot.proxy.impl.DefaultHttpProxyServerBootstrap(props);
  }

  DefaultHttpProxyServer(ServerGroup serverGroup, DefaultHttpProxyServerConfig config) {
    this.serverGroup = serverGroup;
    this.transportProtocol = config.getTransportProtocol();
    this.requestedAddress = config.getRequestedAddress();
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

    long readThrottleBytesPerSecond = config.getReadThrottleBytesPerSecond();
    long writeThrottleBytesPerSecond = config.getWriteThrottleBytesPerSecond();
    if (writeThrottleBytesPerSecond > 0 || readThrottleBytesPerSecond > 0) {
      globalTrafficShapingHandler =
          createGlobalTrafficShapingHandler(
              config.getTransportProtocol(),
              readThrottleBytesPerSecond,
              writeThrottleBytesPerSecond);
    } else {
      globalTrafficShapingHandler = null;
    }
    this.localAddress = config.getLocalAddress();

    String proxyAlias = config.getProxyAlias();
    if (proxyAlias == null) {
      // attempt to resolve the name of the local machine. if it cannot be resolved,
      // use the fallback name.
      String hostname = ProxyUtils.getHostName();
      if (hostname == null) {
        hostname = FALLBACK_PROXY_ALIAS;
      }
      this.proxyAlias = hostname;
    } else {
      this.proxyAlias = proxyAlias;
    }
    this.maxInitialLineLength = config.getMaxInitialLineLength();
    this.maxHeaderSize = config.getMaxHeaderSize();
    this.maxChunkSize = config.getMaxChunkSize();
    this.allowRequestsToOriginServer = config.isAllowRequestsToOriginServer();
    this.acceptProxyProtocol = config.isAcceptProxyProtocol();
    this.sendProxyProtocol = config.isSendProxyProtocol();
    this.serverConnectionPoolConfig = config.getServerConnectionPoolConfig();
    this.useSharedServerConnectionPool = this.serverConnectionPoolConfig.isEnabled();
    this.maxConnectionsPerHost = this.serverConnectionPoolConfig.getMaxConnectionsPerHost();
    this.serverConnectionPoolType = this.serverConnectionPoolConfig.getPoolType();
    this.maxConnections = this.serverConnectionPoolConfig.getMaxConnections();
  }

  /**
   * Creates a new GlobalTrafficShapingHandler for this HttpProxyServer, using this proxy's
   * proxyToServerEventLoop.
   */
  private GlobalTrafficShapingHandler createGlobalTrafficShapingHandler(
      TransportProtocol transportProtocol,
      long readThrottleBytesPerSecond,
      long writeThrottleBytesPerSecond) {
    EventLoopGroup proxyToServerEventLoop = getProxyToServerWorkerFor(transportProtocol);
    return new GlobalTrafficShapingHandler(
        proxyToServerEventLoop,
        writeThrottleBytesPerSecond,
        readThrottleBytesPerSecond,
        TRAFFIC_SHAPING_CHECK_INTERVAL_MS,
        Long.MAX_VALUE);
  }

  boolean isTransparent() {
    return transparent;
  }

  @Override
  public int getIdleConnectionTimeout() {
    return (int) idleConnectionTimeout.toSeconds();
  }

  @Override
  public void setIdleConnectionTimeout(int idleConnectionTimeoutInSeconds) {
    this.idleConnectionTimeout = Duration.ofSeconds(idleConnectionTimeoutInSeconds);
  }

  @Override
  public void setIdleConnectionTimeout(Duration idleConnectionTimeout) {
    this.idleConnectionTimeout = idleConnectionTimeout;
  }

  @Override
  public int getConnectTimeout() {
    return connectTimeout;
  }

  @Override
  public void setConnectTimeout(int connectTimeoutMs) {
    connectTimeout = connectTimeoutMs;
  }

  public HostResolver getServerResolver() {
    return serverResolver;
  }

  public InetSocketAddress getLocalAddress() {
    return localAddress;
  }

  @Override
  public InetSocketAddress getListenAddress() {
    return boundAddress;
  }

  @Override
  public void setThrottle(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
    if (globalTrafficShapingHandler != null) {
      globalTrafficShapingHandler.configure(
          writeThrottleBytesPerSecond, readThrottleBytesPerSecond);
    } else {
      // don't create a GlobalTrafficShapingHandler if throttling was not enabled and
      // is still not enabled
      if (readThrottleBytesPerSecond > 0 || writeThrottleBytesPerSecond > 0) {
        globalTrafficShapingHandler =
            createGlobalTrafficShapingHandler(
                transportProtocol, readThrottleBytesPerSecond, writeThrottleBytesPerSecond);
      }
    }
  }

  public long getReadThrottle() {
    if (globalTrafficShapingHandler != null) {
      return globalTrafficShapingHandler.getReadLimit();
    } else {
      return 0;
    }
  }

  public long getWriteThrottle() {
    if (globalTrafficShapingHandler != null) {
      return globalTrafficShapingHandler.getWriteLimit();
    } else {
      return 0;
    }
  }

  public int getMaxInitialLineLength() {
    return maxInitialLineLength;
  }

  public int getMaxHeaderSize() {
    return maxHeaderSize;
  }

  public int getMaxChunkSize() {
    return maxChunkSize;
  }

  /**
   * Gets the shared ServerConnectionPool for this server. Creates the pool if it doesn't exist.
   *
   * @return the shared pool, or null if the pool is disabled
   */
  @Nullable
  public synchronized ServerConnectionPool getServerConnectionPool() {
    if (!useSharedServerConnectionPool) {
      return null;
    }
    if (serverConnectionPool == null) {
      serverConnectionPool = createServerConnectionPool();
    }
    return serverConnectionPool;
  }

  private ServerConnectionPool createServerConnectionPool() {
    ServerConnectionPoolType poolType = serverConnectionPoolConfig.getPoolType();
    Duration idleTimeout = serverConnectionPoolConfig.getIdleTimeout();
    int maxConnPerHost = serverConnectionPoolConfig.getMaxConnectionsPerHost();
    int maxConn = serverConnectionPoolConfig.getMaxConnections();

    switch (poolType) {
      case COMMONS_POOL2:
        CommonsPoolServerConnectionPool commonsPool =
            new CommonsPoolServerConnectionPool(
                this, globalTrafficShapingHandler, maxConnPerHost, maxConn);
        commonsPool.setIdleTimeout(idleTimeout);
        return commonsPool;
      case STORMPOT:
        StormpotServerConnectionPool stormpotPool =
            new StormpotServerConnectionPool(
                this, globalTrafficShapingHandler, maxConnPerHost, maxConn);
        stormpotPool.setIdleTimeout(idleTimeout);
        return stormpotPool;
      case CONCURRENT_MAP:
      default:
        ConcurrentMapServerConnectionPool concurrentMapPool =
            new ConcurrentMapServerConnectionPool(
                this, globalTrafficShapingHandler, maxConnPerHost, maxConn);
        concurrentMapPool.setIdleTimeout(idleTimeout);
        return concurrentMapPool;
    }
  }

  public boolean isAllowRequestsToOriginServer() {
    return allowRequestsToOriginServer;
  }

  public boolean isAcceptProxyProtocol() {
    return acceptProxyProtocol;
  }

  public boolean isSendProxyProtocol() {
    return sendProxyProtocol;
  }

  @Override
  public HttpProxyServerBootstrap clone() {
    InetSocketAddress clonedAddress =
        new InetSocketAddress(
            requestedAddress.getAddress(),
            requestedAddress.getPort() == 0 ? 0 : requestedAddress.getPort() + 1);

    ServerConnectionPoolConfig poolConfig =
        new ServerConnectionPoolConfig()
            .setEnabled(useSharedServerConnectionPool)
            .setPoolType(serverConnectionPoolType)
            .setMaxConnectionsPerHost(maxConnectionsPerHost)
            .setMaxConnections(maxConnections)
            .setIdleTimeout(serverConnectionPoolConfig.getIdleTimeout());

    DefaultHttpProxyServerConfig serverConfig =
        new DefaultHttpProxyServerConfig()
            .setTransportProtocol(transportProtocol)
            .setRequestedAddress(clonedAddress)
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
            .setReadThrottleBytesPerSecond(
                globalTrafficShapingHandler != null
                    ? globalTrafficShapingHandler.getReadLimit()
                    : 0)
            .setWriteThrottleBytesPerSecond(
                globalTrafficShapingHandler != null
                    ? globalTrafficShapingHandler.getWriteLimit()
                    : 0)
            .setLocalAddress(localAddress)
            .setProxyAlias(proxyAlias)
            .setMaxInitialLineLength(maxInitialLineLength)
            .setMaxHeaderSize(maxHeaderSize)
            .setMaxChunkSize(maxChunkSize)
            .setAllowRequestsToOriginServer(allowRequestsToOriginServer)
            .setAcceptProxyProtocol(acceptProxyProtocol)
            .setSendProxyProtocol(sendProxyProtocol)
            .setServerConnectionPoolConfig(poolConfig);

    return new org.littleshoot.proxy.impl.DefaultHttpProxyServerBootstrap(
        serverGroup, serverConfig);
  }

  @Override
  public void stop() {
    doStop(true);
  }

  @Override
  public void abort() {
    doStop(false);
  }

  /**
   * Performs cleanup necessary to stop the server. Closes all channels opened by the server and
   * unregisters this server from the server group.
   *
   * @param graceful when true, waits for requests to terminate before stopping the server
   */
  protected void doStop(boolean graceful) {
    // only stop the server if it hasn't already been stopped
    if (stopped.compareAndSet(false, true)) {
      if (graceful) {
        LOG.info("Shutting down proxy server gracefully");
      } else {
        LOG.info("Shutting down proxy server immediately (non-graceful)");
      }

      // Close the shared server connection pool
      if (serverConnectionPool != null) {
        serverConnectionPool.closeAll();
      }

      closeAllChannels(graceful);

      serverGroup.unregisterProxyServer(this, graceful);

      // remove the shutdown hook that was added when the proxy was started, since it
      // has now been stopped
      try {
        Runtime.getRuntime().removeShutdownHook(jvmShutdownHook);
      } catch (IllegalStateException e) {
        // ignore -- IllegalStateException means the VM is already shutting down
      }

      LOG.info("Done shutting down proxy server");
    }
  }

  /** Register a new {@link Channel} with this server, for later closing. */
  protected void registerChannel(Channel channel) {
    allChannels.add(channel);
  }

  protected void unregisterChannel(Channel channel) {
    if (channel.isOpen()) {
      // Unlikely to happen, but just in case...
      channel.close();
    }
    allChannels.remove(channel);
  }

  /**
   * Closes all channels opened by this proxy server.
   *
   * @param graceful when false, attempts to shut down all channels immediately and ignores any
   *     channel-closing exceptions
   */
  protected void closeAllChannels(boolean graceful) {
    LOG.info("Closing all channels {}", graceful ? "(graceful)" : "(non-graceful)");

    ChannelGroupFuture future = allChannels.close();

    // if this is a graceful shutdown, log any channel closing failures. if this
    // isn't a graceful shutdown, ignore them.
    if (graceful) {
      try {
        future.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();

        LOG.warn("Interrupted while waiting for channels to shut down gracefully.");
      }

      if (!future.isSuccess()) {
        for (ChannelFuture cf : future) {
          if (!cf.isSuccess()) {
            LOG.info(
                "Unable to close channel. Cause of failure for {} is {}",
                cf.channel(),
                String.valueOf(cf.cause()));
          }
        }
      }
    }
  }

  HttpProxyServer start() {
    if (!serverGroup.isStopped()) {
      LOG.info("Starting proxy at address: {}", requestedAddress);

      serverGroup.registerProxyServer(this);

      doStart();
    } else {
      throw new IllegalStateException(
          "Attempted to start proxy, but proxy's server group is already stopped");
    }

    return this;
  }

  private void doStart() {
    ServerBootstrap serverBootstrap =
        new ServerBootstrap()
            .group(
                serverGroup.getClientToProxyAcceptorPoolForTransport(transportProtocol),
                serverGroup.getClientToProxyWorkerPoolForTransport(transportProtocol));

    ChannelInitializer<Channel> initializer =
        new ChannelInitializer<>() {
          protected void initChannel(Channel ch) {
            new ClientToProxyConnection(
                DefaultHttpProxyServer.this,
                sslEngineSource,
                authenticateSslClients,
                ch.pipeline(),
                globalTrafficShapingHandler);
          }
        };
    switch (transportProtocol) {
      case TCP:
        LOG.info("Proxy listening with TCP transport");
        serverBootstrap.channelFactory(NioServerSocketChannel::new);
        break;
      default:
        throw new UnknownTransportProtocolException(transportProtocol);
    }
    serverBootstrap.childHandler(initializer);
    ChannelFuture future = serverBootstrap.bind(requestedAddress).awaitUninterruptibly();

    Throwable cause = future.cause();
    if (cause != null) {
      abort();
      throw new RuntimeException(cause);
    }

    Channel serverChannel = future.channel();
    registerChannel(serverChannel);
    boundAddress = (InetSocketAddress) serverChannel.localAddress();
    LOG.info("Proxy started at address: {}", boundAddress);

    Runtime.getRuntime().addShutdownHook(jvmShutdownHook);
  }

  protected ChainedProxyManager getChainProxyManager() {
    return chainProxyManager;
  }

  protected MitmManager getMitmManager() {
    return mitmManager;
  }

  protected SslEngineSource getSslEngineSource() {
    return sslEngineSource;
  }

  protected ProxyAuthenticator getProxyAuthenticator() {
    return proxyAuthenticator;
  }

  public HttpFiltersSource getFiltersSource() {
    return filtersSource;
  }

  protected Collection<ActivityTracker> getActivityTrackers() {
    return activityTrackers;
  }

  public String getProxyAlias() {
    return proxyAlias;
  }

  protected EventLoopGroup getProxyToServerWorkerFor(TransportProtocol transportProtocol) {
    return serverGroup.getProxyToServerWorkerPoolForTransport(transportProtocol);
  }
}
