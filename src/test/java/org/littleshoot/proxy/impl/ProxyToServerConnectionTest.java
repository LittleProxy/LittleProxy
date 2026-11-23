package org.littleshoot.proxy.impl;

import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLProtocolException;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxy;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.ChainedProxyType;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.TransportProtocol;

final class ProxyToServerConnectionTest {

  private final DefaultHttpProxyServer proxyServer = mock();
  private final ClientToProxyConnection clientConnection = mock();
  private final HttpFilters filters = mock();
  private final GlobalTrafficShapingHandler trafficHandler = mock();
  private final FlowContext flowContext = mock();
  private final FullFlowContext fullFlowContext = mock();
  private final InetSocketAddress proxyAddress = new InetSocketAddress("127.0.0.1", 9443);
  private final InetSocketAddress hostAddress = new InetSocketAddress("127.0.0.1", 8080);

  @BeforeEach
  void setup() throws UnknownHostException {
    HostResolver hostResolver = mock();

    when(proxyServer.getServerResolver()).thenReturn(hostResolver);
    when(hostResolver.resolve(any(), anyInt())).thenReturn(hostAddress);

    when(clientConnection.flowContext()).thenReturn(flowContext);
    when(clientConnection.flowContextForServerConnection(any())).thenReturn(fullFlowContext);
  }

  @NullMarked
  private ProxyToServerConnection createConnection(ActivityTracker... trackers)
      throws UnknownHostException {
    when(proxyServer.getActivityTrackers()).thenReturn(List.of(trackers));
    return requireNonNull(
        ProxyToServerConnection.create(
            proxyServer, clientConnection, "localhost:8080", filters, null, trafficHandler));
  }

  @Test
  @DisplayName("createForPool returns null when the chained proxy manager has no proxies")
  void createForPoolReturnsNullWhenNoChainedProxies() throws Exception {
    ChainedProxyManager manager = mock();
    when(proxyServer.getChainProxyManager()).thenReturn(manager);
    when(proxyServer.getActivityTrackers()).thenReturn(List.of());

    ProxyToServerConnection connection =
        ProxyToServerConnection.createForPool(
            proxyServer,
            mock(ServerConnectionPool.class),
            clientConnection,
            "localhost:8080",
            null,
            filters,
            new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
            trafficHandler);

    assertThat(connection).isNull();
  }

  @Test
  @DisplayName("createForPool keeps a resolved chained proxy even when the manager returns none")
  void createForPoolKeepsResolvedChainedProxy() throws Exception {
    ChainedProxyManager manager = mock();
    when(proxyServer.getChainProxyManager()).thenReturn(manager);
    when(proxyServer.getActivityTrackers()).thenReturn(List.of());
    ChainedProxy chainedProxy = mock();
    when(chainedProxy.getTransportProtocol()).thenReturn(TransportProtocol.TCP);
    when(chainedProxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
    when(chainedProxy.getChainedProxyAddress()).thenReturn(hostAddress);

    ProxyToServerConnection connection =
        ProxyToServerConnection.createForPool(
            proxyServer,
            mock(ServerConnectionPool.class),
            clientConnection,
            "localhost:8080",
            chainedProxy,
            filters,
            new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
            trafficHandler);

    assertThat(connection).isNotNull();
    assertThat(connection.getRemoteAddress()).isEqualTo(hostAddress);
  }

  @Test
  @DisplayName("switchToWebSocketProtocol replaces the main handler with the pipe handler")
  void switchToWebSocketProtocolReplacesMainHandler() throws Exception {
    ProxyToServerConnection connection = createConnection();
    when(clientConnection.getCurrentState()).thenReturn(ConnectionState.AWAITING_INITIAL);
    EmbeddedChannel channel = new EmbeddedChannel();
    channel.pipeline().addLast("handler", new ChannelInboundHandlerAdapter());
    connection.channel = channel;

    connection.switchToWebSocketProtocol();

    assertThat(channel.pipeline().get("handler")).isNull();
    assertThat(channel.pipeline().get("pipe-to-client")).isNotNull();
    channel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("isAvailableForNewRequest is true for a connected idle connection")
  void isAvailableForNewRequestIsTrueWhenConnectedAndIdle() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.AWAITING_INITIAL);

    assertThat(connection.isAvailableForNewRequest()).isTrue();
  }

  @Test
  @DisplayName("isManagedByPool reflects whether the connection was created for a pool")
  void isManagedByPoolReflectsThePool() throws Exception {
    ProxyToServerConnection direct = createConnection();
    assertThat(direct.isManagedByPool()).isFalse();

    ProxyToServerConnection pooled =
        ProxyToServerConnection.createForPool(
            proxyServer,
            mock(ServerConnectionPool.class),
            clientConnection,
            "localhost:8080",
            null,
            filters,
            mock(HttpRequest.class),
            trafficHandler);
    assertThat(pooled).isNotNull();
    assertThat(pooled.isManagedByPool()).isTrue();
  }

  @Test
  @DisplayName("an idle timeout on an established connection notifies the client")
  void timedOutWhenNotConnectingNotifiesClient() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.AWAITING_INITIAL);

    connection.timedOut();

    verify(clientConnection).timedOut(connection);
  }

  @Test
  @DisplayName("switchToWebSocketProtocol enables tunneling without a main handler")
  void switchToWebSocketProtocolWithoutMainHandlerEnablesTunneling() throws Exception {
    ProxyToServerConnection connection = createConnection();
    EmbeddedChannel channel = new EmbeddedChannel();
    connection.channel = channel;

    connection.switchToWebSocketProtocol();

    assertThat(connection.isTunneling()).isTrue();
    channel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("isConnected covers every connected state")
  void isConnectedCoversEveryConnectedState() throws Exception {
    ProxyToServerConnection connection = createConnection();
    for (ConnectionState state :
        List.of(
            ConnectionState.AWAITING_CHUNK,
            ConnectionState.AWAITING_CONNECT_OK,
            ConnectionState.AWAITING_INITIAL,
            ConnectionState.NEGOTIATING_CONNECT)) {
      connection.become(state);
      assertThat(connection.isConnected()).as("state %s must be connected", state).isTrue();
    }

    connection.become(ConnectionState.CONNECTING);
    assertThat(connection.isConnected()).isFalse();
    assertThat(connection.isAvailableForNewRequest()).isFalse();
  }

  @Test
  @DisplayName("a ProxyConnectException fails the connection flow")
  void proxyConnectExceptionFailsFlow() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.AWAITING_INITIAL);
    ConnectionFlow flow = mock();
    connection.setConnectionFlow(flow);

    ProxyConnectException cause = new ProxyConnectException("proxy refused");
    connection.exceptionCaught(cause);

    verify(flow).fail(cause);
    verify(clientConnection)
        .serverConnectionFailed(connection, ConnectionState.AWAITING_INITIAL, cause);
  }

  @Test
  @DisplayName("an IOException is reported without failing the flow directly")
  void ioExceptionDoesNotFailFlowDirectly() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.AWAITING_INITIAL);
    ConnectionFlow flow = mock();
    connection.setConnectionFlow(flow);

    IOException cause = new IOException("upstream reset");
    connection.exceptionCaught(cause);

    verify(flow, never()).fail(cause);
    verify(clientConnection)
        .serverConnectionFailed(connection, ConnectionState.AWAITING_INITIAL, cause);
  }

  @Test
  @DisplayName("a RejectedExecutionException is reported without failing the flow directly")
  void rejectedExecutionExceptionDoesNotFailFlowDirectly() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.AWAITING_INITIAL);
    ConnectionFlow flow = mock();
    connection.setConnectionFlow(flow);

    RejectedExecutionException cause = new RejectedExecutionException("shutting down");
    connection.exceptionCaught(cause);

    verify(flow, never()).fail(cause);
    verify(clientConnection)
        .serverConnectionFailed(connection, ConnectionState.AWAITING_INITIAL, cause);
  }

  @Test
  @DisplayName("an unexpected exception is reported without failing the flow directly")
  void unexpectedExceptionDoesNotFailFlowDirectly() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.AWAITING_INITIAL);
    ConnectionFlow flow = mock();
    connection.setConnectionFlow(flow);

    RuntimeException cause = new RuntimeException("unexpected");
    connection.exceptionCaught(cause);

    verify(flow, never()).fail(cause);
    verify(clientConnection)
        .serverConnectionFailed(connection, ConnectionState.AWAITING_INITIAL, cause);
  }

  @Test
  @DisplayName("disconnected should clear flow context even when ActivityTracker throws exception")
  void disconnectedShouldClearFlowContextEvenWhenActivityTrackerThrowsException() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .serverDisconnected(any(), any());
    ProxyToServerConnection connection = createConnection(throwingTracker);

    connection.disconnected();

    verify(throwingTracker).serverDisconnected(fullFlowContext, hostAddress);
    verify(clientConnection).clearFlowContextForServerConnection(connection);
  }

  @Test
  @DisplayName("disconnected should clear flow context when no exception occurs")
  void disconnectedShouldClearFlowContextWhenNoException() throws Exception {
    ActivityTracker normalTracker = mock();
    ProxyToServerConnection connection = createConnection(normalTracker);

    connection.disconnected();

    verify(normalTracker).serverDisconnected(fullFlowContext, hostAddress);
    verify(clientConnection).clearFlowContextForServerConnection(connection);
  }

  @Test
  @DisplayName("serverConnected should notify all trackers even if one throws")
  void serverConnectedShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .serverConnected(any(), any());
    ActivityTracker succeedingTracker = mock();
    ProxyToServerConnection connection = createConnection(throwingTracker, succeedingTracker);

    connection.recordServerConnected();

    verify(throwingTracker).serverConnected(fullFlowContext, hostAddress);
    verify(succeedingTracker).serverConnected(fullFlowContext, hostAddress);
  }

  @Test
  @DisplayName(
      "serverDisconnected should notify all trackers even if one throws and still clear context")
  void serverDisconnectedShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .serverDisconnected(any(), any());
    ActivityTracker succeedingTracker = mock();
    ProxyToServerConnection connection = createConnection(throwingTracker, succeedingTracker);

    connection.recordServerDisconnected();

    verify(throwingTracker).serverDisconnected(fullFlowContext, hostAddress);
    verify(succeedingTracker).serverDisconnected(fullFlowContext, hostAddress);
    verify(clientConnection).clearFlowContextForServerConnection(connection);
  }

  @Test
  @DisplayName("connectionSaturated should notify all trackers even if one throws")
  void connectionSaturatedShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .connectionSaturated(any());
    ActivityTracker succeedingTracker = mock();
    ProxyToServerConnection connection = createConnection(throwingTracker, succeedingTracker);

    connection.recordConnectionSaturated();

    verify(throwingTracker).connectionSaturated(fullFlowContext);
    verify(succeedingTracker).connectionSaturated(fullFlowContext);
  }

  @Test
  @DisplayName("connectionWritable should notify all trackers even if one throws")
  void connectionWritableShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception")).when(throwingTracker).connectionWritable(any());
    ActivityTracker succeedingTracker = mock();
    ProxyToServerConnection connection = createConnection(throwingTracker, succeedingTracker);

    connection.recordConnectionWritable();

    verify(throwingTracker).connectionWritable(fullFlowContext);
    verify(succeedingTracker).connectionWritable(fullFlowContext);
  }

  @Test
  @DisplayName("connectionTimedOut should notify all trackers even if one throws")
  void connectionTimedOutShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception")).when(throwingTracker).connectionTimedOut(any());
    ActivityTracker succeedingTracker = mock();
    ProxyToServerConnection connection = createConnection(throwingTracker, succeedingTracker);

    connection.recordConnectionTimedOut();

    verify(throwingTracker).connectionTimedOut(fullFlowContext);
    verify(succeedingTracker).connectionTimedOut(fullFlowContext);
  }

  @Test
  @DisplayName("encrypted chained proxies should prefer peer-aware SSL engines")
  void encryptedChainedProxiesShouldPreferPeerAwareSslEngines() throws Exception {
    ChainedProxy chainedProxy = mock();
    SSLEngine peerAwareEngine = mock();
    when(chainedProxy.newSslEngine("127.0.0.1", 9443)).thenReturn(peerAwareEngine);
    ProxyToServerConnection connection = createConnectionWithChainedProxy(chainedProxy);

    assertThat(connection.newChainedProxySslEngine()).isSameAs(peerAwareEngine);

    verify(chainedProxy).newSslEngine("127.0.0.1", 9443);
    verify(chainedProxy, never()).newSslEngine();
  }

  @Test
  @DisplayName("encrypted chained proxies should fall back to legacy SSL engines")
  void encryptedChainedProxiesShouldFallBackToLegacySslEngines() throws Exception {
    ChainedProxy chainedProxy = mock();
    SSLEngine legacyEngine = mock();
    when(chainedProxy.newSslEngine("127.0.0.1", 9443)).thenReturn(null);
    when(chainedProxy.newSslEngine()).thenReturn(legacyEngine);
    ProxyToServerConnection connection = createConnectionWithChainedProxy(chainedProxy);

    assertThat(connection.newChainedProxySslEngine()).isSameAs(legacyEngine);

    verify(chainedProxy).newSslEngine("127.0.0.1", 9443);
    verify(chainedProxy).newSslEngine();
  }

  @Test
  @DisplayName("connectionExceptionCaught should notify all trackers even if one throws")
  void connectionExceptionCaughtShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .connectionExceptionCaught(any(), any());
    ActivityTracker succeedingTracker = mock();
    ProxyToServerConnection connection = createConnection(throwingTracker, succeedingTracker);
    RuntimeException cause = new RuntimeException("Test cause");

    connection.recordConnectionExceptionCaught(cause);

    verify(throwingTracker).connectionExceptionCaught(fullFlowContext, cause);
    verify(succeedingTracker).connectionExceptionCaught(fullFlowContext, cause);
  }

  @Test
  @DisplayName("a saturated server connection notifies its client")
  void becameSaturatedNotifiesClient() throws Exception {
    ProxyToServerConnection connection = createConnection();

    connection.becameSaturated();

    verify(clientConnection).serverBecameSaturated(connection);
  }

  @Test
  @DisplayName("a writable server connection notifies its client")
  void becameWritableNotifiesClient() throws Exception {
    ProxyToServerConnection connection = createConnection();

    connection.becameWritable();

    verify(clientConnection).serverBecameWriteable(connection);
  }

  @Test
  @DisplayName("a connection with a request in flight is not available for a new request")
  void isAvailableForNewRequestIsFalseWhileRequestInFlight() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.AWAITING_INITIAL);
    EmbeddedChannel channel = new EmbeddedChannel();
    connection.channel = channel;

    connection.writeHttp(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));

    assertThat(connection.isAvailableForNewRequest()).isFalse();
    channel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("a throwing chained-proxy connectionSucceeded callback does not escape")
  void throwingChainedProxyConnectionSucceededDoesNotEscape() throws Exception {
    ChainedProxy chainedProxy = mock();
    when(chainedProxy.getTransportProtocol()).thenReturn(TransportProtocol.TCP);
    when(chainedProxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
    when(chainedProxy.getChainedProxyAddress()).thenReturn(proxyAddress);
    doThrow(new IllegalStateException("tracker failed")).when(chainedProxy).connectionSucceeded();
    ProxyToServerConnection connection = createConnectionWithChainedProxy(chainedProxy);
    connection.become(ConnectionState.CONNECTING);

    assertThatCode(() -> connection.connectionSucceeded(clientConnection, false))
        .doesNotThrowAnyException();
    verify(clientConnection).serverConnectionSucceeded(connection, false);
  }

  @Test
  @DisplayName("addressFor rejects an unparseable host and port")
  void addressForRejectsUnparseableHostAndPort() {
    assertThatThrownBy(
            () -> ProxyToServerConnection.addressFor("example.com:notaport", proxyServer))
        .isInstanceOf(UnknownHostException.class);
  }

  @Test
  @DisplayName("a filter that rejects the upstream response fails the queued writes")
  void filterRejectingUpstreamResponseFailsQueuedWrites() throws Exception {
    ProxyToServerConnection connection = createConnection();
    clientConnection.channel = new EmbeddedChannel();
    connection.become(ConnectionState.CONNECTING);
    DefaultHttpContent content = new DefaultHttpContent(Unpooled.buffer(4).writeInt(1));
    ChannelFuture deferred = connection.write(content);
    assertThat(deferred.isDone()).isFalse();
    when(filters.serverToProxyResponse(any())).thenReturn(null);

    connection.read(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));

    assertThat(deferred.isDone()).as("the queued write must be failed").isTrue();
    assertThat(deferred.isSuccess()).isFalse();
    assertThat(connection.getCurrentState()).isEqualTo(ConnectionState.DISCONNECT_REQUESTED);
    assertThat(content.refCnt()).as("the retained reference must be released").isEqualTo(1);
    content.release();
  }

  @Test
  @DisplayName("a queued write whose dispatch throws is failed instead of left pending")
  void flushPendingWritesFailsWhenDispatchThrows() throws Exception {
    ProxyToServerConnection connection = createConnection();
    clientConnection.channel = new EmbeddedChannel();
    when(clientConnection.getCurrentState()).thenReturn(ConnectionState.AWAITING_INITIAL);
    connection.become(ConnectionState.CONNECTING);
    ChannelFuture deferred = connection.write("not a Netty message");
    assertThat(deferred.isDone()).isFalse();

    connection.connectionSucceeded(clientConnection, false);

    assertThat(deferred.isDone()).as("the queued write must be failed").isTrue();
    assertThat(deferred.isSuccess()).isFalse();
    assertThat(deferred.cause()).isInstanceOf(ClassCastException.class);
  }

  @Test
  @DisplayName("the transport protocol and filters are exposed for the connection")
  void exposesTransportProtocolAndFilters() throws Exception {
    ProxyToServerConnection connection = createConnection();

    assertThat(connection.getTransportProtocol()).isEqualTo(TransportProtocol.TCP);
    assertThat(connection.getHttpFiltersFromProxyServer(mock(HttpRequest.class))).isSameAs(filters);
  }

  @Test
  @DisplayName("createForPool polls the first chained proxy when none is resolved")
  void createForPoolPollsTheFirstChainedProxyWhenNoneIsResolved() throws Exception {
    ChainedProxyManager manager = mock();
    when(proxyServer.getChainProxyManager()).thenReturn(manager);
    when(proxyServer.getActivityTrackers()).thenReturn(List.of());
    ChainedProxy chainedProxy = mock();
    when(chainedProxy.getTransportProtocol()).thenReturn(TransportProtocol.TCP);
    when(chainedProxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
    when(chainedProxy.getChainedProxyAddress()).thenReturn(hostAddress);
    doAnswer(
            invocation -> {
              invocation.<Queue<ChainedProxy>>getArgument(1).add(chainedProxy);
              return null;
            })
        .when(manager)
        .lookupChainedProxies(any(), any(), any());

    ProxyToServerConnection connection =
        ProxyToServerConnection.createForPool(
            proxyServer,
            mock(ServerConnectionPool.class),
            clientConnection,
            "localhost:8080",
            null,
            filters,
            mock(HttpRequest.class),
            trafficHandler);

    assertThat(connection).isNotNull();
    assertThat(connection.getChainedProxy()).isSameAs(chainedProxy);
  }

  @Test
  @DisplayName("flushPendingWrites fails the promise when the write future fails")
  void flushPendingWritesFailsPromiseWhenTheWriteFutureFails() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    channel
        .pipeline()
        .addFirst(
            new ChannelOutboundHandlerAdapter() {
              @Override
              public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IllegalStateException("write rejected"));
              }
            });
    ProxyToServerConnection connection = createConnection();
    connection.channel = channel;
    clientConnection.channel = new EmbeddedChannel();
    when(clientConnection.getCurrentState()).thenReturn(ConnectionState.AWAITING_INITIAL);
    connection.become(ConnectionState.CONNECTING);

    DefaultHttpContent content = new DefaultHttpContent(Unpooled.buffer(4).writeInt(1));
    ChannelFuture result = connection.write(content);
    assertThat(result.isDone()).isFalse();

    connection.connectionSucceeded(clientConnection, false);

    assertThat(result.isDone()).isTrue();
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.cause()).hasMessage("write rejected");

    content.release();
    channel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("a throwing chained proxy disconnected callback does not escape")
  void throwingChainedProxyDisconnectedDoesNotEscape() throws Exception {
    ChainedProxy chainedProxy = mock();
    when(chainedProxy.getTransportProtocol()).thenReturn(TransportProtocol.TCP);
    when(chainedProxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
    when(chainedProxy.getChainedProxyAddress()).thenReturn(hostAddress);
    doThrow(new RuntimeException("tracker failed")).when(chainedProxy).disconnected();
    ProxyToServerConnection connection = createConnectionWithChainedProxy(chainedProxy);
    connection.become(ConnectionState.CONNECTING);

    assertThatCode(connection::disconnected).doesNotThrowAnyException();
  }

  private ProxyToServerConnection createConnectionWithChainedProxy(ChainedProxy chainedProxy)
      throws UnknownHostException {
    ChainedProxyManager chainedProxyManager = mock();
    when(proxyServer.getChainProxyManager()).thenReturn(chainedProxyManager);
    doAnswer(
            invocation -> {
              invocation.<Queue<ChainedProxy>>getArgument(1).add(chainedProxy);
              return null;
            })
        .when(chainedProxyManager)
        .lookupChainedProxies(any(), any(), any());

    when(chainedProxy.getTransportProtocol()).thenReturn(TransportProtocol.TCP);
    when(chainedProxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
    when(chainedProxy.getChainedProxyAddress()).thenReturn(proxyAddress);

    return createConnection();
  }

  /**
   * Helper to set the private {@code disableSslForNonTls} field via reflection.
   *
   * @param connection the connection instance
   * @param value the value to set
   */
  private void setDisableSslForNonTls(ProxyToServerConnection connection, boolean value)
      throws Exception {
    Field field = ProxyToServerConnection.class.getDeclaredField("disableSslForNonTls");
    field.setAccessible(true);
    field.setBoolean(connection, value);
  }

  @Nested
  class ShouldRetryWithoutSsl {
    @ParameterizedTest
    @CsvSource({
      "Remote host terminated the handshake",
      "end of file",
      "not an SSL/TLS record",
      "Connection reset"
    })
    void returnsTrue_forKnownErrorMessages(String errorMessage) throws Exception {
      ProxyToServerConnection connection = createConnection();

      assertThat(connection.shouldRetryWithoutSsl(new SSLHandshakeException(errorMessage)))
          .isTrue();
      assertThat(connection.shouldRetryWithoutSsl(new SSLProtocolException(errorMessage))).isTrue();
      assertThat(connection.shouldRetryWithoutSsl(new SSLException(errorMessage))).isTrue();

      assertThat(
              connection.shouldRetryWithoutSsl(
                  new SSLHandshakeException(errorMessage.toUpperCase(ROOT))))
          .as("case-insensitive")
          .isTrue();
      assertThat(
              connection.shouldRetryWithoutSsl(
                  new SSLHandshakeException(errorMessage.toLowerCase(ROOT))))
          .as("case-insensitive")
          .isTrue();
    }

    @Test
    @DisplayName("should return false for null cause")
    void returnsFalse_forNullCause() throws Exception {
      ProxyToServerConnection connection = createConnection();

      assertThat(connection.shouldRetryWithoutSsl(null)).isFalse();
    }

    @Test
    @DisplayName("should return false for non-SSL exceptions")
    void returnsFalse_forNonSslExceptions() throws Exception {
      ProxyToServerConnection connection = createConnection();

      assertThat(connection.shouldRetryWithoutSsl(new RuntimeException("some error"))).isFalse();
    }

    @Test
    @DisplayName("should return false for SSL exceptions without a message")
    void returnsFalse_forSslExceptionWithoutMessage() throws Exception {
      ProxyToServerConnection connection = createConnection();

      assertThat(connection.shouldRetryWithoutSsl(new SSLException((String) null))).isFalse();
    }

    @Test
    @DisplayName("should return false when already retried without SSL")
    void returnsFalse_WhenAlreadyRetried() throws Exception {
      ProxyToServerConnection connection = createConnection();

      setDisableSslForNonTls(connection, true); // TODO

      SSLHandshakeException cause =
          new SSLHandshakeException("Remote host terminated the handshake");
      assertThat(connection.shouldRetryWithoutSsl(cause)).isFalse();
    }

    @Test
    @DisplayName("should return false for SSL exceptions with non-matching messages")
    void returnsFalse_rorNonMatchingMessages() throws Exception {
      ProxyToServerConnection connection = createConnection();

      SSLHandshakeException cause = new SSLHandshakeException("certificate expired");
      assertThat(connection.shouldRetryWithoutSsl(cause)).isFalse();
    }
  }
}
