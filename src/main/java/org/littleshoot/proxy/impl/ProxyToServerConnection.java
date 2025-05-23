package org.littleshoot.proxy.impl;

import com.google.common.net.HostAndPort;
import com.google.errorprone.annotations.CheckReturnValue;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandRequest;
import io.netty.handler.codec.socksx.v4.Socks4ClientDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ClientEncoder;
import io.netty.handler.codec.socksx.v4.Socks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.codec.socksx.v4.Socks4CommandType;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5ClientEncoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxy;
import org.littleshoot.proxy.ChainedProxyAdapter;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.ChainedProxyType;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.MitmManager;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolException;
import org.littleshoot.proxy.extras.HAProxyMessageEncoder;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;

import static org.littleshoot.proxy.impl.ConnectionState.AWAITING_CHUNK;
import static org.littleshoot.proxy.impl.ConnectionState.AWAITING_CONNECT_OK;
import static org.littleshoot.proxy.impl.ConnectionState.AWAITING_INITIAL;
import static org.littleshoot.proxy.impl.ConnectionState.CONNECTING;
import static org.littleshoot.proxy.impl.ConnectionState.DISCONNECTED;
import static org.littleshoot.proxy.impl.ConnectionState.HANDSHAKING;

/**
 * <p>
 * Represents a connection from our proxy to a server on the web.
 * ProxyConnections are reused fairly liberally, and can go from disconnected to
 * connected, back to disconnected and so on.
 * </p>
 *
 * <p>
 * Connecting a {@link ProxyToServerConnection} can involve more than just
 * connecting the underlying {@link Channel}. In particular, the connection may
 * use encryption (i.e. TLS) and it may also establish an HTTP CONNECT tunnel.
 * The various steps involved in fully establishing a connection are
 * encapsulated in the property {@link #connectionFlow}, which is initialized in
 * {@link #initializeConnectionFlow()}.
 * </p>
 */
@Sharable
@NullMarked
public class ProxyToServerConnection extends ProxyConnection<HttpResponse> {
    // Pipeline handler names:
    private static final String HTTP_ENCODER_NAME = "encoder";
    private static final String HTTP_DECODER_NAME = "decoder";
    private static final String HTTP_PROXY_ENCODER_NAME = "proxy-protocol-encoder";
    private static final String HTTP_REQUEST_WRITTEN_MONITOR_NAME = "requestWrittenMonitor";
    private static final String HTTP_RESPONSE_READ_MONITOR_NAME = "responseReadMonitor";
    private static final String SOCKS_ENCODER_NAME = "socksEncoder";
    private static final String SOCKS_DECODER_NAME = "socksDecoder";
    private static final String MAIN_HANDLER_NAME = "handler";
    private final ClientToProxyConnection clientConnection;
    private final ProxyToServerConnection serverConnection = this;
    private volatile TransportProtocol transportProtocol;
    private volatile ChainedProxyType chainedProxyType;
    private volatile InetSocketAddress remoteAddress;
    private volatile InetSocketAddress localAddress;
    @Nullable
    private volatile AddressResolverGroup<?> remoteAddressResolver;
    @Nullable
    private volatile String username;
    @Nullable
    private volatile String password;
    private final String serverHostAndPort;
    @Nullable
    private volatile ChainedProxy chainedProxy;
    private final Queue<ChainedProxy> availableChainedProxies;

    /**
     * The filters to apply to response/chunks received from server.
     */
    private volatile HttpFilters currentFilters;

    /**
     * Encapsulates the flow for establishing a connection, which can vary
     * depending on how things are configured.
     */
    @Nullable
    private volatile ConnectionFlow connectionFlow;

    /**
     * Disables SNI when initializing connection flow in {@link #initializeConnectionFlow()}. This value is set to true
     * when retrying a connection without SNI to work around Java's SNI handling issue (see
     * {@link #connectionFailed(Throwable)}).
     */
    private volatile boolean disableSni;

    /**
     * While we're in the process of connecting, it's possible that we'll
     * receive a new message to write. This lock helps us synchronize and wait
     * for the connection to be established before writing the next message.
     */
    private final Object connectLock = new Object();

    /**
     * This is the initial request received prior to connecting. We keep track
     * of it so that we can process it after connection finishes.
     */
    @Nullable
    private volatile HttpRequest initialRequest;

    /**
     * Keeps track of HttpRequests that have been issued so that we can
     * associate them with responses that we get back
     */
    @Nullable
    private volatile HttpRequest currentHttpRequest;

    /**
     * While we're doing a chunked transfer, this keeps track of the initial
     * HttpResponse object for our transfer (which is useful for its headers).
     */
    @Nullable
    private volatile HttpResponse currentHttpResponse;

    /**
     * Limits bandwidth when throttling is enabled.
     */
    private final GlobalTrafficShapingHandler trafficHandler;

    /**
     * Create a new ProxyToServerConnection.
     */
    @Nullable
    @CheckReturnValue
    static ProxyToServerConnection create(DefaultHttpProxyServer proxyServer,
            ClientToProxyConnection clientConnection,
            String serverHostAndPort,
            HttpFilters initialFilters,
            HttpRequest initialHttpRequest,
            GlobalTrafficShapingHandler globalTrafficShapingHandler)
            throws UnknownHostException {
        Queue<ChainedProxy> chainedProxies = new ConcurrentLinkedQueue<>();
        ChainedProxyManager chainedProxyManager = proxyServer
                .getChainProxyManager();
        if (chainedProxyManager != null) {
            chainedProxyManager.lookupChainedProxies(initialHttpRequest,
                    chainedProxies, clientConnection.getClientDetails());
            if (chainedProxies.isEmpty()) {
                // ChainedProxyManager returned no proxies, can't connect
                return null;
            }
        }
        return new ProxyToServerConnection(proxyServer,
                clientConnection,
                serverHostAndPort,
                chainedProxies.poll(),
                chainedProxies,
                initialFilters,
                globalTrafficShapingHandler);
    }

    private ProxyToServerConnection(
            DefaultHttpProxyServer proxyServer,
            ClientToProxyConnection clientConnection,
            String serverHostAndPort,
            ChainedProxy chainedProxy,
            Queue<ChainedProxy> availableChainedProxies,
            HttpFilters initialFilters,
            GlobalTrafficShapingHandler globalTrafficShapingHandler)
            throws UnknownHostException {
        super(DISCONNECTED, proxyServer, true);
        this.clientConnection = clientConnection;
        this.serverHostAndPort = serverHostAndPort;
        this.chainedProxy = chainedProxy;
        this.availableChainedProxies = availableChainedProxies;
        this.trafficHandler = globalTrafficShapingHandler;
        this.currentFilters = initialFilters;

        // Report connection status to HttpFilters
        currentFilters.proxyToServerConnectionQueued();

        setupConnectionParameters();
    }

    /* *************************************************************************
     * Reading
     **************************************************************************/

    @Override
    protected void read(Object msg) {
        if (isConnecting()) {
            LOG.debug(
                    "In the middle of connecting, forwarding message to connection flow: {}",
                    msg);
            connectionFlow.read(msg);
        } else {
            super.read(msg);
        }
    }

    @Override
    protected void readHAProxyMessage(HAProxyMessage msg) {
        // NO-OP,
        // We never expect server to send a proxy protocol message.
    }

    @Override
    ConnectionState readHTTPInitial(HttpResponse httpResponse) {
        LOG.debug("Received raw response: {}", httpResponse);

        if (httpResponse.decoderResult().isFailure()) {
            LOG.debug("Could not parse response from server. Decoder result: {}", httpResponse.decoderResult().toString());

            // create a "substitute" Bad Gateway response from the server, since we couldn't understand what the actual
            // response from the server was. set the keep-alive on the substitute response to false so the proxy closes
            // the connection to the server, since we don't know what state the server thinks the connection is in.
            FullHttpResponse substituteResponse = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_GATEWAY,
                    "Unable to parse response from server");
            HttpUtil.setKeepAlive(substituteResponse, false);
            httpResponse = substituteResponse;
        }

        currentFilters.serverToProxyResponseReceiving();

        rememberCurrentResponse(httpResponse);
        respondWith(httpResponse);

        if (ProxyUtils.isChunked(httpResponse)) {
            return AWAITING_CHUNK;
        } else {
            currentFilters.serverToProxyResponseReceived();

            return AWAITING_INITIAL;
        }
    }

    @Override
    protected void readHTTPChunk(HttpContent chunk) {
        respondWith(chunk);
    }

    @Override
    protected void readRaw(ByteBuf buf) {
        clientConnection.write(buf);
    }

    /**
     * <p>
     * Responses to HEAD requests aren't supposed to have content, but Netty
     * doesn't know that any given response is to a HEAD request, so it needs to
     * be told that there's no content so that it doesn't hang waiting for it.
     * </p>
     *
     * <p>
     * See the documentation for {@link HttpResponseDecoder} for information
     * about why HEAD requests need special handling.
     * </p>
     *
     * <p>
     * Thanks to <a href="https://github.com/nataliakoval">nataliakoval</a> for
     * pointing out that with connections being reused as they are, this needs
     * to be sensitive to the current request.
     * </p>
     */
    private class HeadAwareHttpResponseDecoder extends HttpResponseDecoder {

        public HeadAwareHttpResponseDecoder(int maxInitialLineLength,
                int maxHeaderSize, int maxChunkSize) {
            super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage httpMessage) {
            // The current HTTP Request can be null when this proxy is
            // negotiating a CONNECT request with a chained proxy
            // while it is running as a MITM. Since the response to a
            // CONNECT request does not have any content, we return true.
            if(currentHttpRequest == null) {
                return true;
            } else {
                return ProxyUtils.isHEAD(currentHttpRequest) || super.isContentAlwaysEmpty(httpMessage);
            }
        }
    }

    /* *************************************************************************
     * Writing
     **************************************************************************/

    /**
     * Like {@link #write(Object)} and also sets the current filters to the
     * given value.
     */
    void write(Object msg, HttpFilters filters) {
        currentFilters = filters;
        write(msg);
    }

    @Override
    ChannelFuture write(Object msg) {
        LOG.debug("Requested write of {}", msg);

        if (msg instanceof ReferenceCounted) {
            LOG.debug("Retaining reference counted message");
            ((ReferenceCounted) msg).retain();
        }

        if (is(DISCONNECTED) && msg instanceof HttpRequest) {
            LOG.debug("Currently disconnected, connect and then write the message");
            connectAndWrite((HttpRequest) msg);
            return clientConnection.channel.newSucceededFuture();
        } else {
            if (isConnecting()) {
                synchronized (connectLock) {
                    if (isConnecting()) {
                        LOG.debug("Attempted to write while still in the process of connecting, waiting for connection.");
                        clientConnection.stopReading();
                        try {
                            connectLock.wait(30000);
                        } catch (InterruptedException ie) {
                            LOG.warn("Interrupted while waiting for connect monitor");
                        }
                    }
                }
            }

            // only write this message if a connection was established and is not in the process of disconnecting or
            // already disconnected
            if (isConnecting() || getCurrentState().isDisconnectingOrDisconnected()) {
                LOG.debug("Connection failed or timed out while waiting to write message to server. Message will be discarded: {}", msg);
                if (msg instanceof ReferenceCounted ) {
                    // fix: when connection was disconnecting or disconnected, retain() the refCnt = 2, can not release the msg , final leading to OutOfDirectMemoryError.
                    ((ReferenceCounted) msg).release();
                }
                return channel.newFailedFuture(new Exception("Connection failed or timed out while waiting to write message to server. Message will be discarded."));
            }

            LOG.debug("Using existing connection to: {}", remoteAddress);
            return doWrite(msg);
        }
    }

    @Override
    protected ChannelFuture writeHttp(HttpObject httpObject) {
        if (chainedProxy != null) {
            chainedProxy.filterRequest(httpObject);
        }
        if (httpObject instanceof HttpRequest) {
            // Remember that we issued this HttpRequest for later
            currentHttpRequest = (HttpRequest) httpObject;
        }
        return super.writeHttp(httpObject);
    }

    /* *************************************************************************
     * Lifecycle
     **************************************************************************/

    @Override
    protected void become(ConnectionState newState) {
        // Report connection status to HttpFilters
        if (getCurrentState() == DISCONNECTED && newState == CONNECTING) {
            currentFilters.proxyToServerConnectionStarted();
        } else if (getCurrentState() == CONNECTING) {
            if (newState == HANDSHAKING) {
                currentFilters.proxyToServerConnectionSSLHandshakeStarted();
            } else if (newState == AWAITING_INITIAL) {
                currentFilters.proxyToServerConnectionSucceeded(ctx);
            } else if (newState == DISCONNECTED) {
                currentFilters.proxyToServerConnectionFailed();
            }
        } else if (getCurrentState() == HANDSHAKING) {
            if (newState == AWAITING_INITIAL) {
                currentFilters.proxyToServerConnectionSucceeded(ctx);
            } else if (newState == DISCONNECTED) {
                currentFilters.proxyToServerConnectionFailed();
            }
        } else if (getCurrentState() == AWAITING_CHUNK
                && newState != AWAITING_CHUNK) {
            currentFilters.serverToProxyResponseReceived();
        }

        super.become(newState);
    }

    @Override
    protected void becameSaturated() {
        super.becameSaturated();
        clientConnection.serverBecameSaturated(this);
    }

    @Override
    protected void becameWritable() {
        super.becameWritable();
        clientConnection.serverBecameWriteable(this);
    }

    @Override
    protected void timedOut() {
        super.timedOut();
        clientConnection.timedOut(this);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        if (chainedProxy != null) {
            // Let the ChainedProxy know that we disconnected
            try {
                chainedProxy.disconnected();
            } catch (Exception e) {
                LOG.error("Unable to record connectionFailed", e);
            }
        }
        clientConnection.serverDisconnected(this);
    }

    @Override
    protected void exceptionCaught(Throwable cause) {
        try {
            if (cause instanceof ProxyConnectException) {
                LOG.info("A ProxyConnectException occurred on ProxyToServerConnection: " + cause.getMessage());
                connectionFlow.fail(cause);
            } else if (cause instanceof IOException) {
                // IOExceptions are expected errors, for example when a server drops the connection. rather than flood
                // the logs with stack traces for these expected exceptions, log the message at the INFO level and the
                // stack trace at the DEBUG level.
                LOG.info("An IOException occurred on ProxyToServerConnection: " + cause.getMessage());
                LOG.debug("An IOException occurred on ProxyToServerConnection", cause);
            } else if (cause instanceof RejectedExecutionException) {
                LOG.info("An executor rejected a read or write operation on the ProxyToServerConnection (this is normal if the proxy is shutting down). Message: " + cause.getMessage());
                LOG.debug("A RejectedExecutionException occurred on ProxyToServerConnection", cause);
            } else {
                LOG.error("Caught an exception on ProxyToServerConnection", cause);
            }
        } finally {
            if (!is(DISCONNECTED)) {
                LOG.info("Disconnecting open connection to server");
                disconnect();
                clientConnection.serverConnectionFailed(this, getCurrentState(), cause);
            }
        }
        // This can happen if we couldn't make the initial connection due
        // to something like an unresolved address, for example, or a timeout.
        // There will not be any requests written on an unopened
        // connection, so there should not be any further action to take here.
    }

    /* *************************************************************************
     * State Management
     **************************************************************************/
    public TransportProtocol getTransportProtocol() {
        return transportProtocol;
    }

    public ChainedProxyType getChainedProxyType() {
        return chainedProxyType;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public String getServerHostAndPort() {
        return serverHostAndPort;
    }

    public boolean hasUpstreamChainedProxy() {
        return getChainedProxyAddress() != null;
    }

    @Nullable
    public InetSocketAddress getChainedProxyAddress() {
        return chainedProxy == null ? null : chainedProxy
                .getChainedProxyAddress();
    }

    @Nullable
    public ChainedProxy getChainedProxy() {
        return chainedProxy;
    }

    @Nullable
    public HttpRequest getInitialRequest() {
        return initialRequest;
    }

    @Override
    protected HttpFilters getHttpFiltersFromProxyServer(HttpRequest httpRequest) {
        return currentFilters;
    }

    /* *************************************************************************
     * Private Implementation
     **************************************************************************/

    /**
     * Keeps track of the current HttpResponse so that we can associate its
     * headers with future related chunks for this same transfer.
     */
    private void rememberCurrentResponse(HttpResponse response) {
        LOG.debug("Remembering the current response.");
        // We need to make a copy here because the response will be
        // modified in various ways before we need to do things like
        // analyze response headers for whether to close the
        // connection (which may not happen for a while for large, chunked
        // responses, for example).
        currentHttpResponse = ProxyUtils.copyMutableResponseFields(response);
    }

    /**
     * Respond to the client with the given {@link HttpObject}.
     */
    private void respondWith(HttpObject httpObject) {
        clientConnection.respond(this, currentFilters, currentHttpRequest,
                currentHttpResponse, httpObject);
    }

    /**
     * Configures the connection to the upstream server and begins the {@link ConnectionFlow}.
     *
     * @param initialRequest the current HTTP request being handled
     */
    private void connectAndWrite(HttpRequest initialRequest) {
        LOG.debug("Starting new connection to: {}", remoteAddress);

        // Remember our initial request so that we can write it after connecting
        this.initialRequest = initialRequest;
        initializeConnectionFlow();
        connectionFlow.start();
    }

    /**
     * This method initializes our {@link ConnectionFlow} based on however this connection has been configured. If
     * the {@link #disableSni} value is true, this method will not pass peer information to the MitmManager when
     * handling CONNECTs.
     */
    private void initializeConnectionFlow() {
        connectionFlow = new ConnectionFlow(clientConnection, this,
                connectLock)
                .then(ConnectChannel);

        if (hasUpstreamChainedProxy()) {
            if (chainedProxy.requiresEncryption()) {
                connectionFlow.then(serverConnection.EncryptChannel(chainedProxy.newSslEngine()));
            }
            switch (chainedProxyType) {
                case SOCKS4:
                    connectionFlow.then(SOCKS4CONNECTWithChainedProxy);
                    break;
                case SOCKS5:
                    connectionFlow.then(SOCKS5InitialRequest);
                    break;
                default:
                    break;
            }
        }

        if (ProxyUtils.isCONNECT(initialRequest)) {
            // If we're chaining to an upstream HTTP proxy, forward the CONNECT request.
            // Do not chain the CONNECT request for SOCKS proxies.
            if (hasUpstreamChainedProxy() && (chainedProxyType == ChainedProxyType.HTTP)) {
                connectionFlow.then(serverConnection.HTTPCONNECTWithChainedProxy);
            }

            MitmManager mitmManager = proxyServer.getMitmManager();
            boolean isMitmEnabled = currentFilters.proxyToServerAllowMitm() && mitmManager != null;

            if (isMitmEnabled) {
                // When MITM is enabled and when chained proxy is set up, remoteAddress
                // will be the chained proxy's address. So we use serverHostAndPort
                // which is the end server's address.
                HostAndPort parsedHostAndPort = HostAndPort.fromString(serverHostAndPort);

                // SNI may be disabled for this request due to a previous failed attempt to connect to the server
                // with SNI enabled.
                if (disableSni) {
                    connectionFlow.then(serverConnection.EncryptChannel(proxyServer.getMitmManager()
                            .serverSslEngine()));
                } else {
                    connectionFlow.then(serverConnection.EncryptChannel(proxyServer.getMitmManager()
                            .serverSslEngine(parsedHostAndPort.getHost(), parsedHostAndPort.getPort())));
                }

            	connectionFlow
                        .then(clientConnection.RespondCONNECTSuccessful)
                        .then(serverConnection.MitmEncryptClientChannel);
            } else {
                connectionFlow.then(serverConnection.StartTunneling)
                        .then(clientConnection.RespondCONNECTSuccessful)
                        .then(clientConnection.StartTunneling);
            }
        }
    }

    private void addFirstOrReplaceHandler(String name, ChannelHandler handler) {
        if (channel.pipeline().context(name) != null) {
            channel.pipeline().replace(name, name, handler);
        }
        else {
            channel.pipeline().addFirst(name, handler);
        }
    }

    private void removeHandlerIfPresent(String name) {
        removeHandlerIfPresent(channel.pipeline(), name);
    }

    /**
     * Opens the socket connection.
     */
    private final ConnectionFlowStep ConnectChannel = new ConnectionFlowStep(this,
            CONNECTING) {
        @Override
        boolean shouldExecuteOnEventLoop() {
            return false;
        }

        @Override
        protected Future<?> execute() {
            Bootstrap cb = new Bootstrap()
                .group(proxyServer.getProxyToServerWorkerFor(transportProtocol))
                .resolver(remoteAddressResolver);

            switch (transportProtocol) {
                case TCP:
                    LOG.debug("Connecting to server with TCP");
                    cb.channelFactory(NioSocketChannel::new);
                    break;
                default:
                    throw new UnknownTransportProtocolException(transportProtocol);
            }

            cb.handler(new ChannelInitializer<>() {
                protected void initChannel(Channel ch) {
                    initChannelPipeline(ch.pipeline());
                }
            });
            cb.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                    proxyServer.getConnectTimeout());

            if (localAddress != null) {
                return cb.connect(remoteAddress, localAddress);
            } else {
                return cb.connect(remoteAddress);
            }
        }
    };

    /**
     * Writes the HTTP CONNECT to the server and waits for a 200 response.
     */
    private final ConnectionFlowStep HTTPCONNECTWithChainedProxy = new ConnectionFlowStep(
            this, AWAITING_CONNECT_OK) {
        protected Future<?> execute() {
            LOG.debug("Handling CONNECT request through Chained Proxy");
            chainedProxy.filterRequest(initialRequest);
            MitmManager mitmManager = proxyServer.getMitmManager();
            boolean isMitmEnabled = currentFilters.proxyToServerAllowMitm() && mitmManager != null;
            /*
             * We ignore the LastHttpContent which we read from the client
             * connection when we are negotiating connect (see readHttp()
             * in ProxyConnection). This cannot be ignored while we are
             * doing MITM + Chained Proxy because the HttpRequestEncoder
             * of the ProxyToServerConnection will be in an invalid state
             * when the next request is written. Writing the EmptyLastContent
             * resets its state.
             */
            if(isMitmEnabled){
                ChannelFuture future = writeToChannel(initialRequest);
                future.addListener((ChannelFutureListener) arg0 -> {
                    if(arg0.isSuccess()){
                        writeToChannel(LastHttpContent.EMPTY_LAST_CONTENT);
                    }
                });
                return future;
            } else {
                return writeToChannel(initialRequest);
            }
        }

        void onSuccess(ConnectionFlow flow) {
            // Do nothing, since we want to wait for the CONNECT response to
            // come back
        }

        void read(ConnectionFlow flow, Object msg) {
            // Here we're handling the response from a chained proxy to our
            // earlier CONNECT request
            boolean connectOk = false;
            if (msg instanceof HttpResponse) {
                HttpResponse httpResponse = (HttpResponse) msg;
                int statusCode = httpResponse.status().code();
                if (statusCode >= 200 && statusCode <= 299) {
                    connectOk = true;
                }
            }
            if (connectOk) {
                flow.advance();
            } else {
                flow.fail();
            }
        }
    };

    /**
     * Establishes a SOCKS4 connection.
     */
    private final ConnectionFlowStep SOCKS4CONNECTWithChainedProxy = new ConnectionFlowStep(
            this, AWAITING_CONNECT_OK) {

        @Override
        protected Future<?> execute() {
            InetSocketAddress destinationAddress;
            try {
                destinationAddress = addressFor(serverHostAndPort, proxyServer);
            } catch (UnknownHostException e) {
                return channel.newFailedFuture(e);
            }

            DefaultSocks4CommandRequest connectRequest = new DefaultSocks4CommandRequest(
                Socks4CommandType.CONNECT, destinationAddress.getHostString(), destinationAddress.getPort());

            addFirstOrReplaceHandler(SOCKS_ENCODER_NAME, Socks4ClientEncoder.INSTANCE);
            addFirstOrReplaceHandler(SOCKS_DECODER_NAME, new Socks4ClientDecoder());
            return writeToChannel(connectRequest);
        }

        @Override
        void read(ConnectionFlow flow, Object msg) {
            removeHandlerIfPresent(SOCKS_ENCODER_NAME);
            removeHandlerIfPresent(SOCKS_DECODER_NAME);
            if (msg instanceof Socks4CommandResponse) {
                if (((Socks4CommandResponse) msg).status() == Socks4CommandStatus.SUCCESS) {
                    flow.advance();
                    return;
                }
            }
            flow.fail();
        }

        @Override
        void onSuccess(ConnectionFlow flow) {
            // Do not advance the flow until the SOCKS response has been parsed
        }
    };

    /**
     * Initiates a SOCKS5 connection.
     */
    private final ConnectionFlowStep SOCKS5InitialRequest = new ConnectionFlowStep(
            this, AWAITING_CONNECT_OK) {

        @Override
        protected Future<?> execute() {
            List<Socks5AuthMethod> authMethods = new ArrayList<>(2);
            authMethods.add(Socks5AuthMethod.NO_AUTH);
            if ((username != null) || (password != null)) {
                authMethods.add(Socks5AuthMethod.PASSWORD);
            }
            DefaultSocks5InitialRequest initialRequest = new DefaultSocks5InitialRequest(authMethods);

            addFirstOrReplaceHandler(SOCKS_ENCODER_NAME, Socks5ClientEncoder.DEFAULT);
            addFirstOrReplaceHandler(SOCKS_DECODER_NAME, new Socks5InitialResponseDecoder());
            return writeToChannel(initialRequest);
        }

        @Override
        void read(ConnectionFlow flow, Object msg) {
            if (msg instanceof Socks5InitialResponse) {
                Socks5AuthMethod selectedAuthMethod = ((Socks5InitialResponse) msg).authMethod();

                final boolean authSuccess;
                if (selectedAuthMethod == Socks5AuthMethod.NO_AUTH) {
                    // Immediately proceed to SOCKS CONNECT
                    flow.first(SOCKS5CONNECTRequestWithChainedProxy);
                    authSuccess = true;
                }
                else if (selectedAuthMethod == Socks5AuthMethod.PASSWORD) {
                    // Insert a password negotiation step:
                    flow.first(SOCKS5SendPasswordCredentials);
                    authSuccess = true;
                }
                else {
                    // Server returned Socks5AuthMethod.UNACCEPTED or a method we do not support
                    authSuccess = false;
                }

                if (authSuccess) {
                    flow.advance();
                    return;
                }
            }
            flow.fail();
        }

        @Override
        void onSuccess(ConnectionFlow flow) {
            // Do not advance the flow until the SOCKS response has been parsed
        }
    };

    /**
     * Sends SOCKS5 password credentials after {@link #SOCKS5InitialRequest} has completed.
     */
    private final ConnectionFlowStep SOCKS5SendPasswordCredentials = new ConnectionFlowStep(
            this, AWAITING_CONNECT_OK) {

        @Override
        protected Future<?> execute() {
            DefaultSocks5PasswordAuthRequest authRequest = new DefaultSocks5PasswordAuthRequest(
                username != null ? username : "", password != null ? password : "");

            addFirstOrReplaceHandler(SOCKS_DECODER_NAME, new Socks5PasswordAuthResponseDecoder());
            return writeToChannel(authRequest);
        }

        @Override
        void read(ConnectionFlow flow, Object msg) {
            if (msg instanceof Socks5PasswordAuthResponse) {
                if (((Socks5PasswordAuthResponse) msg).status() == Socks5PasswordAuthStatus.SUCCESS) {
                    flow.first(SOCKS5CONNECTRequestWithChainedProxy);
                    flow.advance();
                    return;
                }
            }
            flow.fail();
        }

        @Override
        void onSuccess(ConnectionFlow flow) {
            // Do not advance the flow until the SOCKS response has been parsed
        }
    };

    /**
     * Establishes a SOCKS5 connection after {@link #SOCKS5InitialRequest} and
     * (optionally) {@link #SOCKS5SendPasswordCredentials} have completed.
     */
    private final ConnectionFlowStep SOCKS5CONNECTRequestWithChainedProxy = new ConnectionFlowStep(
            this, AWAITING_CONNECT_OK) {

        @Override
        protected Future<?> execute() {
            InetSocketAddress destinationAddress = unresolvedAddressFor(serverHostAndPort);
            DefaultSocks5CommandRequest connectRequest = new DefaultSocks5CommandRequest(
                Socks5CommandType.CONNECT, Socks5AddressType.DOMAIN, destinationAddress.getHostString(), destinationAddress.getPort());

            addFirstOrReplaceHandler(SOCKS_DECODER_NAME, new Socks5CommandResponseDecoder());
            return writeToChannel(connectRequest);
        }

        @Override
        void read(ConnectionFlow flow, Object msg) {
            removeHandlerIfPresent(SOCKS_ENCODER_NAME);
            removeHandlerIfPresent(SOCKS_DECODER_NAME);
            if (msg instanceof Socks5CommandResponse) {
                if (((Socks5CommandResponse) msg).status() == Socks5CommandStatus.SUCCESS) {
                    flow.advance();
                    return;
                }
            }
            flow.fail();
        }

        @Override
        void onSuccess(ConnectionFlow flow) {
            // Do not advance the flow until the SOCKS response has been parsed
        }
    };

    /**
     * <p>
     * Encrypts the client channel based on our server {@link SSLSession}.
     * </p>
     *
     * <p>
     * This does not wait for the handshake to finish so that we can go on and
     * respond to the CONNECT request.
     * </p>
     */
    private final ConnectionFlowStep MitmEncryptClientChannel = new ConnectionFlowStep(
            this, HANDSHAKING) {
        @Override
        boolean shouldExecuteOnEventLoop() {
            return false;
        }

        @Override
        boolean shouldSuppressInitialRequest() {
            return true;
        }

        @Override
        protected Future<?> execute() {
            return clientConnection
                    .encrypt(proxyServer.getMitmManager()
                            .clientSslEngineFor(initialRequest, sslEngine.getSession()), false)
                    .addListener(
                            future -> {
                                if (future.isSuccess()) {
                                    clientConnection.setMitming(true);
                                }
                            });
        }
    };

    /**
     * Called when the connection to the server or upstream chained proxy fails. This method may return true to indicate
     * that the connection should be retried. If returning true, this method must set up the connection itself.
     *
     * @param cause the reason that our attempt to connect failed (can be null)
     * @return true if we are trying to fall back to another connection
     */
    protected boolean connectionFailed(Throwable cause)
            throws UnknownHostException {
        // unlike a browser, java throws an exception when receiving an unrecognized_name TLS warning, even if the server
        // sends back a valid certificate for the expected host. we can retry the connection without SNI to allow the proxy
        // to connect to these misconfigured hosts. we should only retry the connection without SNI if the connection
        // failure happened when SNI was enabled, to prevent never-ending connection attempts due to SNI warnings.
        if (!disableSni && (cause instanceof SSLProtocolException) || (cause instanceof SSLHandshakeException)) {
            // unfortunately java does not expose the specific TLS alert number (112), so we have to look for the
            // unrecognized_name string in the exception's message
            if (cause.getMessage() != null && cause.getMessage().contains("unrecognized_name")) {
                LOG.debug("Failed to connect to server due to an unrecognized_name SSL warning. Retrying connection without SNI.");

                // disable SNI, re-setup the connection, and restart the connection flow
                disableSni = true;
                resetConnectionForRetry();
                connectAndWrite(initialRequest);

                return true;
            }
        }

        // the connection issue wasn't due to an unrecognized_name error, or the connection attempt failed even after
        // disabling SNI. before falling back to a chained proxy, re-enable SNI.
        disableSni = false;

        if (chainedProxy != null) {
            LOG.info("Connection to upstream server via chained proxy failed", cause);
            // Let the ChainedProxy know that we were unable to connect
            chainedProxy.connectionFailed(cause);
        } else {
            LOG.info("Connection to upstream server failed", cause);
        }

        // attempt to connect using a chained proxy, if available
        chainedProxy = availableChainedProxies.poll();
        if (chainedProxy != null) {
            LOG.info("Retrying connecting using the next available chained proxy");

            resetConnectionForRetry();

            connectAndWrite(initialRequest);
            return true;
        }

        resetInitialRequest();

        // no chained proxy fallback or other retry mechanism available
        return false;
    }

    /**
     * Convenience method to prepare to retry this connection. Closes the connection's channel and sets up
     * the connection again using {@link #setupConnectionParameters()}.
     *
     * @throws UnknownHostException when {@link #setupConnectionParameters()} is unable to resolve the hostname
     */
    private void resetConnectionForRetry() throws UnknownHostException {
        // Remove ourselves as handler on the old context
        ctx.pipeline().remove(this);
        ctx.close();
        ctx = null;

        setupConnectionParameters();
    }

    /**
     * Set up our connection parameters based on server address and chained
     * proxies.
     *
     * @throws UnknownHostException when unable to resolve the hostname to an IP address
     */
    private void setupConnectionParameters() throws UnknownHostException {
        if (chainedProxy != null
                && chainedProxy != ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION) {
            transportProtocol = chainedProxy.getTransportProtocol();
            chainedProxyType = chainedProxy.getChainedProxyType();
            localAddress = chainedProxy.getLocalAddress();
            remoteAddress = chainedProxy.getChainedProxyAddress();
            remoteAddressResolver = DefaultAddressResolverGroup.INSTANCE;
            username = chainedProxy.getUsername();
            password = chainedProxy.getPassword();
        } else {
            transportProtocol = TransportProtocol.TCP;
            chainedProxyType = ChainedProxyType.HTTP;
            username = null;
            password = null;

            // Report DNS resolution to HttpFilters
            remoteAddress = currentFilters.proxyToServerResolutionStarted(serverHostAndPort);

            // save the hostname and port of the unresolved address in hostAndPort, in case name resolution fails
            String hostAndPort = null;
            try {
                if (remoteAddress == null) {
                    hostAndPort = serverHostAndPort;
                    remoteAddress = addressFor(serverHostAndPort, proxyServer);
                } else if (remoteAddress.isUnresolved()) {
                    // filter returned an unresolved address, so resolve it using the proxy server's resolver
                    hostAndPort = HostAndPort.fromParts(remoteAddress.getHostName(), remoteAddress.getPort()).toString();
                    remoteAddress = proxyServer.getServerResolver().resolve(remoteAddress.getHostName(), remoteAddress.getPort());
                }
            } catch (UnknownHostException e) {
                // unable to resolve the hostname to an IP address. notify the filters of the failure before allowing the
                // exception to bubble up.
                currentFilters.proxyToServerResolutionFailed(hostAndPort);

                throw e;
            }

            currentFilters.proxyToServerResolutionSucceeded(serverHostAndPort, remoteAddress);


            localAddress = proxyServer.getLocalAddress();
        }
    }

    /**
     * Initialize our {@link ChannelPipeline} to connect the upstream server.
     * LittleProxy acts as a client here.
     *
     * A {@link ChannelPipeline} invokes the read (Inbound) handlers in
     * ascending ordering of the list and then the write (Outbound) handlers in
     * descending ordering.
     *
     * Regarding the Javadoc of {@link HttpObjectAggregator} it's needed to have
     * the {@link HttpResponseEncoder} or {@link HttpRequestEncoder} before the
     * {@link HttpObjectAggregator} in the {@link ChannelPipeline}.
     */
    private void initChannelPipeline(ChannelPipeline pipeline) {

        if (trafficHandler != null) {
            pipeline.addLast("global-traffic-shaping", trafficHandler);
        }

        pipeline.addLast("bytesReadMonitor", bytesReadMonitor);
        pipeline.addLast("bytesWrittenMonitor", bytesWrittenMonitor);

        if ( proxyServer.isSendProxyProtocol()) {
            pipeline.addLast(HTTP_PROXY_ENCODER_NAME, new HAProxyMessageEncoder());
        }
        pipeline.addLast(HTTP_ENCODER_NAME, new HttpRequestEncoder());
        pipeline.addLast(HTTP_DECODER_NAME, new HeadAwareHttpResponseDecoder(
                proxyServer.getMaxInitialLineLength(),
                proxyServer.getMaxHeaderSize(),
                proxyServer.getMaxChunkSize()));

        // Enable aggregation for filtering if necessary
        int numberOfBytesToBuffer = proxyServer.getFiltersSource()
                .getMaximumResponseBufferSizeInBytes();
        if (numberOfBytesToBuffer > 0) {
            aggregateContentForFiltering(pipeline, numberOfBytesToBuffer);
        }

        pipeline.addLast(HTTP_RESPONSE_READ_MONITOR_NAME, responseReadMonitor);
        pipeline.addLast(HTTP_REQUEST_WRITTEN_MONITOR_NAME, requestWrittenMonitor);

        // Set idle timeout
        pipeline.addLast(
                "idle",
                new IdleStateHandler(0, 0, proxyServer
                        .getIdleConnectionTimeout()));

        pipeline.addLast(MAIN_HANDLER_NAME, this);
    }

    /**
     * <p>
     * Do all the stuff that needs to be done after our {@link ConnectionFlow}
     * has succeeded.
     * </p>
     *
     * @param shouldForwardInitialRequest
     *            whether we should forward the initial HttpRequest to
     *            the server after the connection has been established.
     */
    void connectionSucceeded(boolean shouldForwardInitialRequest) {
        become(AWAITING_INITIAL);
        if (chainedProxy != null) {
            // Notify the ChainedProxy that we successfully connected
            try {
                chainedProxy.connectionSucceeded();
            } catch (Exception e) {
                LOG.error("Unable to record connectionSucceeded", e);
            }
        }
        clientConnection.serverConnectionSucceeded(this,
                shouldForwardInitialRequest);

        if (shouldForwardInitialRequest) {
            LOG.debug("Writing initial request: {}", initialRequest);
            write(initialRequest);
        } else {
            LOG.debug("Dropping initial request: {}", initialRequest);
        }

        // we're now done with the initialRequest: it's either been forwarded to the upstream server (HTTP requests), or
        // completely dropped (HTTPS CONNECTs). if the initialRequest is reference counted (typically because the HttpObjectAggregator is in
        // the pipeline to generate FullHttpRequests), we need to manually release it to avoid a memory leak.
        resetInitialRequest();
    }

    private void resetInitialRequest() {
        if (initialRequest instanceof ReferenceCounted) {
            ((ReferenceCounted)initialRequest).release();
        }
    }

    /**
     * Build an {@link InetSocketAddress} for the given hostAndPort.
     *
     * @param hostAndPort String representation of the host and port
     * @param proxyServer the current {@link DefaultHttpProxyServer}
     * @return a resolved InetSocketAddress for the specified hostAndPort
     * @throws UnknownHostException if hostAndPort could not be resolved, or if the input string could not be parsed into
     *          a host and port.
     */
    public static InetSocketAddress addressFor(String hostAndPort, DefaultHttpProxyServer proxyServer)
            throws UnknownHostException {
        HostAndPort parsedHostAndPort;
        try {
            parsedHostAndPort = HostAndPort.fromString(hostAndPort);
        } catch (IllegalArgumentException e) {
            // we couldn't understand the hostAndPort string, so there is no way we can resolve it.
            throw new UnknownHostException(hostAndPort);
        }

        String host = parsedHostAndPort.getHost();
        int port = parsedHostAndPort.getPortOrDefault(80);

        return proxyServer.getServerResolver().resolve(host, port);
    }

    /**
     * Similar to {@link #addressFor(String, DefaultHttpProxyServer)} except that it does
     * not resolve the address.
     * @param hostAndPort the host and port to parse.
     * @return an unresolved {@link InetSocketAddress}.
     */
    private static InetSocketAddress unresolvedAddressFor(String hostAndPort) {
        HostAndPort parsedHostAndPort = HostAndPort.fromString(hostAndPort);
        String host = parsedHostAndPort.getHost();
        int port = parsedHostAndPort.getPortOrDefault(80);
        return InetSocketAddress.createUnresolved(host, port);
    }

    void switchToWebSocketProtocol() {
        final List<String> orderedHandlersToRemove = Arrays.asList(HTTP_REQUEST_WRITTEN_MONITOR_NAME,
                HTTP_RESPONSE_READ_MONITOR_NAME, HTTP_PROXY_ENCODER_NAME, HTTP_ENCODER_NAME, HTTP_DECODER_NAME);
        if (channel.pipeline().get(MAIN_HANDLER_NAME) != null) {
            channel.pipeline().replace(MAIN_HANDLER_NAME, "pipe-to-client",
                    new ProxyConnectionPipeHandler(clientConnection));
        }
        orderedHandlersToRemove.forEach(this::removeHandlerIfPresent);
        tunneling = true;
    }

    /* *************************************************************************
     * Activity Tracking/Statistics
     *
     * We track statistics on bytes, requests and responses by adding handlers
     * at the appropriate parts of the pipeline (see initChannelPipeline()).
     **************************************************************************/

    private final BytesReadMonitor bytesReadMonitor = new BytesReadMonitor() {
        @Override
        protected void bytesRead(int numberOfBytes) {
            FullFlowContext flowContext = new FullFlowContext(clientConnection,
                    ProxyToServerConnection.this);
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesReceivedFromServer(flowContext, numberOfBytes);
            }
        }
    };

    private final ResponseReadMonitor responseReadMonitor = new ResponseReadMonitor() {
        @Override
        protected void responseRead(HttpResponse httpResponse) {
            FullFlowContext flowContext = new FullFlowContext(clientConnection,
                    ProxyToServerConnection.this);
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.responseReceivedFromServer(flowContext, httpResponse);
            }
        }
    };

    private final BytesWrittenMonitor bytesWrittenMonitor = new BytesWrittenMonitor() {
        @Override
        protected void bytesWritten(int numberOfBytes) {
            FullFlowContext flowContext = new FullFlowContext(clientConnection,
                    ProxyToServerConnection.this);
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesSentToServer(flowContext, numberOfBytes);
            }
        }
    };

    private final RequestWrittenMonitor requestWrittenMonitor = new RequestWrittenMonitor() {
        @Override
        protected void requestWriting(HttpRequest httpRequest) {
            FullFlowContext flowContext = new FullFlowContext(clientConnection,
                    ProxyToServerConnection.this);
            try {
                for (ActivityTracker tracker : proxyServer
                        .getActivityTrackers()) {
                    tracker.requestSentToServer(flowContext, httpRequest);
                }
            } catch (Throwable t) {
                LOG.warn("Error while invoking ActivityTracker on request", t);
            }

            currentFilters.proxyToServerRequestSending();
        }

        @Override
        protected void requestWritten(HttpRequest httpRequest) {
        }

        @Override
        protected void contentWritten(HttpContent httpContent) {
            if (httpContent instanceof LastHttpContent) {
                currentFilters.proxyToServerRequestSent();
            }
        }
    };

}
