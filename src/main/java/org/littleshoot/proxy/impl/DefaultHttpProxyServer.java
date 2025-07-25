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
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.DefaultHostResolver;
import org.littleshoot.proxy.DnsSecServerResolver;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersSource;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.HttpProxyServerBootstrap;
import org.littleshoot.proxy.MitmManager;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.SslEngineSource;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
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

import static java.util.Objects.requireNonNullElseGet;

/**
 * <p>
 * Primary implementation of an {@link HttpProxyServer}.
 * </p>
 *
 * <p>
 * {@link DefaultHttpProxyServer} is bootstrapped by calling
 * {@link #bootstrap()} or {@link #bootstrapFromFile(String)}, and then calling
 * {@link DefaultHttpProxyServerBootstrap#start()}. For example:
 * </p>
 *
 * <pre>
 * DefaultHttpProxyServer server =
 *         DefaultHttpProxyServer
 *                 .bootstrap()
 *                 .withPort(8090)
 *                 .start();
 * </pre>
 *
 */
public class DefaultHttpProxyServer implements HttpProxyServer {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpProxyServer.class);

    /**
     * The interval in ms at which the GlobalTrafficShapingHandler will run to compute and throttle the
     * proxy-to-server bandwidth.
     */
    private static final long TRAFFIC_SHAPING_CHECK_INTERVAL_MS = 250L;

    private static final int MAX_INITIAL_LINE_LENGTH_DEFAULT = 8192;
    private static final int MAX_HEADER_SIZE_DEFAULT = 8192*2;
    private static final int MAX_CHUNK_SIZE_DEFAULT = 8192*2;

    /**
     * The proxy alias to use in the Via header if no explicit proxy alias is specified and the hostname of the local
     * machine cannot be resolved.
     */
    private static final String FALLBACK_PROXY_ALIAS = "littleproxy";

    /**
     * Our {@link ServerGroup}. Multiple proxy servers can share the same
     * ServerGroup in order to reuse threads and other such resources.
     */
    private final ServerGroup serverGroup;

    private final TransportProtocol transportProtocol;
    /*
    * The address that the server will attempt to bind to.
    */
    private final InetSocketAddress requestedAddress;
    /*
    * The actual address to which the server is bound. May be different from the requestedAddress in some circumstances,
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

    /**
     * The alias or pseudonym for this proxy, used when adding the Via header.
     */
    private final String proxyAlias;

    /**
     * True when the proxy has already been stopped by calling {@link #stop()} or {@link #abort()}.
     */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Track all ActivityTrackers for tracking proxying activity.
     */
    private final Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<>();

    /**
     * Keep track of all channels created by this proxy server for later shutdown when the proxy is stopped.
     */
    private final ChannelGroup allChannels =
            new DefaultChannelGroup("HTTP-Proxy-Server", GlobalEventExecutor.INSTANCE, true);

    /**
     * JVM shutdown hook to shut down this proxy server. Declared as a class-level variable to allow removing the shutdown hook when the
     * proxy server is stopped normally.
     */
    private final Thread jvmShutdownHook = new Thread(this::abort, "LittleProxy-JVM-shutdown-hook");

    /**
     * Bootstrap a new {@link DefaultHttpProxyServer} starting from scratch.
     */
    public static HttpProxyServerBootstrap bootstrap() {
        return new DefaultHttpProxyServerBootstrap();
    }

    /**
     * Bootstrap a new {@link DefaultHttpProxyServer} using defaults from the
     * given file.
     */
    public static HttpProxyServerBootstrap bootstrapFromFile(String path) {
        final File propsFile = new File(path);
        Properties props = new Properties();

        if (propsFile.isFile()) {
            try (InputStream is = new FileInputStream(propsFile)) {
                props.load(is);
            } catch (final IOException e) {
                LOG.warn("Could not load props file?", e);
            }
        }

        return new DefaultHttpProxyServerBootstrap(props);
    }

    /**
     * Creates a new proxy server.
     *
     * @param serverGroup
     *            our ServerGroup for shared thread pools and such
     * @param transportProtocol
     *            The protocol to use for data transport
     * @param requestedAddress
     *            The address on which this server will listen
     * @param sslEngineSource
     *            (optional) if specified, this Proxy will encrypt inbound
     *            connections from clients using an {@link SSLEngine} obtained
     *            from this {@link SslEngineSource}.
     * @param authenticateSslClients
     *            Indicate whether to authenticate clients when using SSL
     * @param proxyAuthenticator
     *            (optional) If specified, requests to the proxy will be
     *            authenticated using HTTP BASIC authentication per the provided
     *            {@link ProxyAuthenticator}
     * @param chainProxyManager
     *            The proxy to send requests to if chaining proxies. Typically
     *            <code>null</code>.
     * @param mitmManager
     *            The {@link MitmManager} to use for man in the middling
     *            CONNECT requests
     * @param filtersSource
     *            Source for {@link HttpFilters}
     * @param transparent
     *            If true, this proxy will run as a transparent proxy. This will
     *            not modify the response, and will only modify the request to
     *            amend the URI if the target is the origin server (to comply
     *            with RFC 7230 section 5.3.1).
     * @param idleConnectionTimeout
     *            The timeout (in seconds) for auto-closing idle connections.
     * @param activityTrackers
     *            for tracking activity on this proxy
     * @param connectTimeout
     *            number of milliseconds to wait to connect to the upstream
     *            server
     * @param serverResolver
     *            the {@link HostResolver} to use for resolving server addresses
     * @param readThrottleBytesPerSecond
     *            read throttle bandwidth
     * @param writeThrottleBytesPerSecond
     *            write throttle bandwidth
     * @param allowRequestsToOriginServer
     *            when true, allow the proxy to handle requests that contain an origin-form URI, as defined in RFC 7230 5.3.1
     * @param acceptProxyProtocol when true, the proxy will accept a proxy protocol header from client
     * @param sendProxyProtocol when true, the proxy will send a proxy protocol header to the server
     */
    private DefaultHttpProxyServer(ServerGroup serverGroup,
            TransportProtocol transportProtocol,
            InetSocketAddress requestedAddress,
            SslEngineSource sslEngineSource,
            boolean authenticateSslClients,
            ProxyAuthenticator proxyAuthenticator,
            ChainedProxyManager chainProxyManager,
            MitmManager mitmManager,
            HttpFiltersSource filtersSource,
            boolean transparent,
            Duration idleConnectionTimeout,
            Collection<ActivityTracker> activityTrackers,
            int connectTimeout,
            HostResolver serverResolver,
            long readThrottleBytesPerSecond,
            long writeThrottleBytesPerSecond,
            InetSocketAddress localAddress,
            String proxyAlias,
            int maxInitialLineLength,
            int maxHeaderSize,
            int maxChunkSize,
            boolean allowRequestsToOriginServer,
            boolean acceptProxyProtocol,
            boolean sendProxyProtocol) {
        this.serverGroup = serverGroup;
        this.transportProtocol = transportProtocol;
        this.requestedAddress = requestedAddress;
        this.sslEngineSource = sslEngineSource;
        this.authenticateSslClients = authenticateSslClients;
        this.proxyAuthenticator = proxyAuthenticator;
        this.chainProxyManager = chainProxyManager;
        this.mitmManager = mitmManager;
        this.filtersSource = filtersSource;
        this.transparent = transparent;
        this.idleConnectionTimeout = idleConnectionTimeout;
        if (activityTrackers != null) {
            this.activityTrackers.addAll(activityTrackers);
        }
        this.connectTimeout = connectTimeout;
        this.serverResolver = serverResolver;

        if (writeThrottleBytesPerSecond > 0 || readThrottleBytesPerSecond > 0) {
            globalTrafficShapingHandler = createGlobalTrafficShapingHandler(transportProtocol, readThrottleBytesPerSecond, writeThrottleBytesPerSecond);
        } else {
            globalTrafficShapingHandler = null;
        }
        this.localAddress = localAddress;

        if (proxyAlias == null) {
            // attempt to resolve the name of the local machine. if it cannot be resolved, use the fallback name.
            String hostname = ProxyUtils.getHostName();
            if (hostname == null) {
                hostname = FALLBACK_PROXY_ALIAS;
            }
            this.proxyAlias = hostname;
        } else {
            this.proxyAlias = proxyAlias;
        }
        this.maxInitialLineLength = maxInitialLineLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxChunkSize = maxChunkSize;
        this.allowRequestsToOriginServer = allowRequestsToOriginServer;
        this.acceptProxyProtocol = acceptProxyProtocol;
        this.sendProxyProtocol = sendProxyProtocol;
    }

    /**
     * Creates a new GlobalTrafficShapingHandler for this HttpProxyServer, using this proxy's proxyToServerEventLoop.
     */
    private GlobalTrafficShapingHandler createGlobalTrafficShapingHandler(TransportProtocol transportProtocol, long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
        EventLoopGroup proxyToServerEventLoop = getProxyToServerWorkerFor(transportProtocol);
        return new GlobalTrafficShapingHandler(proxyToServerEventLoop,
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
            globalTrafficShapingHandler.configure(writeThrottleBytesPerSecond, readThrottleBytesPerSecond);
        } else {
            // don't create a GlobalTrafficShapingHandler if throttling was not enabled and is still not enabled
            if (readThrottleBytesPerSecond > 0 || writeThrottleBytesPerSecond > 0) {
                globalTrafficShapingHandler = createGlobalTrafficShapingHandler(transportProtocol, readThrottleBytesPerSecond, writeThrottleBytesPerSecond);
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
        return new DefaultHttpProxyServerBootstrap(serverGroup,
                transportProtocol,
                new InetSocketAddress(requestedAddress.getAddress(),
                        requestedAddress.getPort() == 0 ? 0 : requestedAddress.getPort() + 1),
                    sslEngineSource,
                    authenticateSslClients,
                    proxyAuthenticator,
                    chainProxyManager,
                    mitmManager,
                    filtersSource,
                    transparent,
                    idleConnectionTimeout,
                    activityTrackers,
                    connectTimeout,
                    serverResolver,
                    globalTrafficShapingHandler != null ? globalTrafficShapingHandler.getReadLimit() : 0,
                    globalTrafficShapingHandler != null ? globalTrafficShapingHandler.getWriteLimit() : 0,
                    localAddress,
                    proxyAlias,
                    maxInitialLineLength,
                    maxHeaderSize,
                    maxChunkSize,
                    allowRequestsToOriginServer);
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
     * Performs cleanup necessary to stop the server. Closes all channels opened by the server and unregisters this
     * server from the server group.
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

            closeAllChannels(graceful);

            serverGroup.unregisterProxyServer(this, graceful);

            // remove the shutdown hook that was added when the proxy was started, since it has now been stopped
            try {
                Runtime.getRuntime().removeShutdownHook(jvmShutdownHook);
            } catch (IllegalStateException e) {
                // ignore -- IllegalStateException means the VM is already shutting down
            }

            LOG.info("Done shutting down proxy server");
        }
    }

    /**
     * Register a new {@link Channel} with this server, for later closing.
     */
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
     * @param graceful when false, attempts to shut down all channels immediately and ignores any channel-closing exceptions
     */
    protected void closeAllChannels(boolean graceful) {
        LOG.info("Closing all channels {}", graceful ? "(graceful)" : "(non-graceful)");

        ChannelGroupFuture future = allChannels.close();

        // if this is a graceful shutdown, log any channel closing failures. if this isn't a graceful shutdown, ignore them.
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
                        LOG.info("Unable to close channel. Cause of failure for {} is {}", cf.channel(), String.valueOf(cf.cause()));
                    }
                }
            }
        }
    }

    private HttpProxyServer start() {
        if (!serverGroup.isStopped()) {
            LOG.info("Starting proxy at address: {}", requestedAddress);

            serverGroup.registerProxyServer(this);

            doStart();
        } else {
            throw new IllegalStateException("Attempted to start proxy, but proxy's server group is already stopped");
        }

        return this;
    }

    private void doStart() {
        ServerBootstrap serverBootstrap = new ServerBootstrap().group(
                serverGroup.getClientToProxyAcceptorPoolForTransport(transportProtocol),
                serverGroup.getClientToProxyWorkerPoolForTransport(transportProtocol));

        ChannelInitializer<Channel> initializer = new ChannelInitializer<>() {
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

    // TODO: refactor bootstrap into a separate class
    @NullMarked
    private static class DefaultHttpProxyServerBootstrap implements HttpProxyServerBootstrap {
        private String name = "LittleProxy";
        @Nullable
        private ServerGroup serverGroup;
        private TransportProtocol transportProtocol = TransportProtocol.TCP;
        @Nullable
        private InetSocketAddress requestedAddress;
        private int port = 8080;
        private boolean allowLocalOnly = true;
        @Nullable
        private SslEngineSource sslEngineSource;
        private boolean authenticateSslClients = true;
        @Nullable
        private ProxyAuthenticator proxyAuthenticator;
        @Nullable
        private ChainedProxyManager chainProxyManager;
        @Nullable
        private MitmManager mitmManager;
        private HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter();
        private boolean transparent;
        private Duration idleConnectionTimeout = Duration.ofSeconds(70);
        private final Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<>();
        private int connectTimeout = 40000;
        private HostResolver serverResolver = new DefaultHostResolver();
        private long readThrottleBytesPerSecond;
        private long writeThrottleBytesPerSecond;
        @Nullable
        private InetSocketAddress localAddress;
        @Nullable
        private String proxyAlias;
        private int clientToProxyAcceptorThreads = ServerGroup.DEFAULT_INCOMING_ACCEPTOR_THREADS;
        private int clientToProxyWorkerThreads = ServerGroup.DEFAULT_INCOMING_WORKER_THREADS;
        private int proxyToServerWorkerThreads = ServerGroup.DEFAULT_OUTGOING_WORKER_THREADS;
        private int maxInitialLineLength = MAX_INITIAL_LINE_LENGTH_DEFAULT;
        private int maxHeaderSize = MAX_HEADER_SIZE_DEFAULT;
        private int maxChunkSize = MAX_CHUNK_SIZE_DEFAULT;
        private boolean allowRequestToOriginServer;
        private boolean acceptProxyProtocol;
        private boolean sendProxyProtocol;

        private DefaultHttpProxyServerBootstrap() {
        }

        private DefaultHttpProxyServerBootstrap(
                ServerGroup serverGroup,
                TransportProtocol transportProtocol,
                InetSocketAddress requestedAddress,
                SslEngineSource sslEngineSource,
                boolean authenticateSslClients,
                ProxyAuthenticator proxyAuthenticator,
                ChainedProxyManager chainProxyManager,
                MitmManager mitmManager,
                HttpFiltersSource filtersSource,
                boolean transparent, Duration idleConnectionTimeout,
                @Nullable Collection<ActivityTracker> activityTrackers,
                int connectTimeout, HostResolver serverResolver,
                long readThrottleBytesPerSecond,
                long  writeThrottleBytesPerSecond,
                InetSocketAddress localAddress,
                String proxyAlias,
                int maxInitialLineLength,
                int maxHeaderSize,
                int maxChunkSize,
                boolean allowRequestToOriginServer) {
            this.serverGroup = serverGroup;
            this.transportProtocol = transportProtocol;
            this.requestedAddress = requestedAddress;
            this.port = requestedAddress.getPort();
            this.sslEngineSource = sslEngineSource;
            this.authenticateSslClients = authenticateSslClients;
            this.proxyAuthenticator = proxyAuthenticator;
            this.chainProxyManager = chainProxyManager;
            this.mitmManager = mitmManager;
            this.filtersSource = filtersSource;
            this.transparent = transparent;
            this.idleConnectionTimeout = idleConnectionTimeout;
            if (activityTrackers != null) {
                this.activityTrackers.addAll(activityTrackers);
            }
            this.connectTimeout = connectTimeout;
            this.serverResolver = serverResolver;
            this.readThrottleBytesPerSecond = readThrottleBytesPerSecond;
            this.writeThrottleBytesPerSecond = writeThrottleBytesPerSecond;
            this.localAddress = localAddress;
            this.proxyAlias = proxyAlias;
            this.maxInitialLineLength = maxInitialLineLength;
        	this.maxHeaderSize = maxHeaderSize;
        	this.maxChunkSize = maxChunkSize;
        	this.allowRequestToOriginServer = allowRequestToOriginServer;
        }

        private DefaultHttpProxyServerBootstrap(Properties props) {
            withUseDnsSec(ProxyUtils.extractBooleanDefaultFalse(
                    props, "dnssec"));
            transparent = ProxyUtils.extractBooleanDefaultFalse(
                    props, "transparent");
            idleConnectionTimeout = Duration.ofSeconds(ProxyUtils.extractInt(
                    props, "idle_connection_timeout"));
            connectTimeout = ProxyUtils.extractInt(props,
                    "connect_timeout", 0);
            maxInitialLineLength = ProxyUtils.extractInt(props,
                    "max_initial_line_length", MAX_INITIAL_LINE_LENGTH_DEFAULT);
            maxHeaderSize = ProxyUtils.extractInt(props,
                    "max_header_size", MAX_HEADER_SIZE_DEFAULT);
            maxChunkSize = ProxyUtils.extractInt(props,
                    "max_chunk_size", MAX_CHUNK_SIZE_DEFAULT);
        }

        @Override
        public HttpProxyServerBootstrap withName(String name) {
            this.name = name;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withTransportProtocol(
                TransportProtocol transportProtocol) {
            this.transportProtocol = transportProtocol;
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
        @NullMarked
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
        public HttpProxyServerBootstrap withAllowLocalOnly(
                boolean allowLocalOnly) {
            this.allowLocalOnly = allowLocalOnly;
            return this;
        }

        @Override
        @Deprecated
        public HttpProxyServerBootstrap withListenOnAllAddresses(boolean listenOnAllAddresses) {
            LOG.warn("withListenOnAllAddresses() is deprecated and will be removed in a future release. Use withNetworkInterface().");
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withSslEngineSource(
                SslEngineSource sslEngineSource) {
            this.sslEngineSource = sslEngineSource;
            if (mitmManager != null) {
                LOG.warn("Enabled encrypted inbound connections with man in the middle. "
                        + "These are mutually exclusive - man in the middle will be disabled.");
                mitmManager = null;
            }
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withAuthenticateSslClients(
                boolean authenticateSslClients) {
            this.authenticateSslClients = authenticateSslClients;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withProxyAuthenticator(
                ProxyAuthenticator proxyAuthenticator) {
            this.proxyAuthenticator = proxyAuthenticator;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withChainProxyManager(
                ChainedProxyManager chainProxyManager) {
            this.chainProxyManager = chainProxyManager;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withManInTheMiddle(
                MitmManager mitmManager) {
            this.mitmManager = mitmManager;
            if (sslEngineSource != null) {
                LOG.warn("Enabled man in the middle with encrypted inbound connections. "
                        + "These are mutually exclusive - encrypted inbound connections will be disabled.");
                sslEngineSource = null;
            }
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withFiltersSource(
                HttpFiltersSource filtersSource) {
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
        public HttpProxyServerBootstrap withTransparent(
                boolean transparent) {
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
        public HttpProxyServerBootstrap withConnectTimeout(
                int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withServerResolver(
                HostResolver serverResolver) {
            this.serverResolver = serverResolver;
            return this;
        }
	@Override
	public HttpProxyServerBootstrap withServerGroup(
		ServerGroup group) {
	   serverGroup = group;
	   return this;
	}

        @Override
        public HttpProxyServerBootstrap plusActivityTracker(
                ActivityTracker activityTracker) {
            activityTrackers.add(activityTracker);
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withThrottling(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
            this.readThrottleBytesPerSecond = readThrottleBytesPerSecond;
            this.writeThrottleBytesPerSecond = writeThrottleBytesPerSecond;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withMaxInitialLineLength(int maxInitialLineLength){
        	this.maxInitialLineLength = maxInitialLineLength;
        	return this;
        }

        @Override
        public HttpProxyServerBootstrap withMaxHeaderSize(int maxHeaderSize){
        	this.maxHeaderSize = maxHeaderSize;
        	return this;
        }

        @Override
        public HttpProxyServerBootstrap withMaxChunkSize(int maxChunkSize){
        	this.maxChunkSize = maxChunkSize;
        	return this;
        }

        @Override
        public HttpProxyServerBootstrap withAllowRequestToOriginServer(boolean allowRequestToOriginServer) {
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
        public HttpProxyServer start() {
            return build().start();
        }

        @Override
        public HttpProxyServerBootstrap withThreadPoolConfiguration(ThreadPoolConfiguration configuration) {
            clientToProxyAcceptorThreads = configuration.getAcceptorThreads();
            clientToProxyWorkerThreads = configuration.getClientToProxyWorkerThreads();
            proxyToServerWorkerThreads = configuration.getProxyToServerWorkerThreads();
            return this;
        }

        private DefaultHttpProxyServer build() {
            final ServerGroup serverGroup = requireNonNullElseGet(this.serverGroup, () -> new ServerGroup(name, clientToProxyAcceptorThreads, clientToProxyWorkerThreads, proxyToServerWorkerThreads));

            return new DefaultHttpProxyServer(serverGroup,
              transportProtocol, determineListenAddress(),
              sslEngineSource, authenticateSslClients,
              proxyAuthenticator, chainProxyManager, mitmManager,
              filtersSource, transparent,
              idleConnectionTimeout, activityTrackers, connectTimeout,
              serverResolver, readThrottleBytesPerSecond, writeThrottleBytesPerSecond,
              localAddress, proxyAlias, maxInitialLineLength, maxHeaderSize, maxChunkSize,
              allowRequestToOriginServer, acceptProxyProtocol, sendProxyProtocol);
        }

        private InetSocketAddress determineListenAddress() {
            if (requestedAddress != null) {
                return requestedAddress;
            } else {
                // Binding only to localhost can significantly improve the
                // security of the proxy.
                if (allowLocalOnly) {
                    return new InetSocketAddress("127.0.0.1", port);
                } else {
                    return new InetSocketAddress(port);
                }
            }
        }
    }
}
