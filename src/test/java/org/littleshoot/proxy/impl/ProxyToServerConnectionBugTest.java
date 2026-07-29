package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ActivityTrackerAdapter;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.extras.HAProxyMessageEncoder;
import org.littleshoot.proxy.extras.ProxyProtocolMessage;

class ProxyToServerConnectionBugTest {

  private DefaultHttpProxyServer mockProxyServer;
  private ClientToProxyConnection mockClientConnection;
  private HttpFilters mockFilters;
  private GlobalTrafficShapingHandler mockTrafficHandler;
  private HostResolver mockHostResolver;
  private FlowContext mockClientFlowContext;
  private FullFlowContext mockFlowContext;

  @BeforeEach
  void setup() throws Exception {
    mockProxyServer = mock();
    mockClientConnection = mock();
    mockFilters = mock();
    mockTrafficHandler = mock();
    mockHostResolver = mock();

    when(mockProxyServer.getServerResolver()).thenReturn(mockHostResolver);
    when(mockHostResolver.resolve(any(), anyInt()))
        .thenReturn(new InetSocketAddress("127.0.0.1", 8080));

    mockClientFlowContext = mock();
    when(mockClientConnection.flowContext()).thenReturn(mockClientFlowContext);
    mockFlowContext = mock();
    when(mockClientConnection.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mockFlowContext);
  }

  private ProxyToServerConnection createConnection(List<ActivityTracker> trackers)
      throws Exception {
    when(mockProxyServer.getActivityTrackers()).thenReturn(trackers);
    return ProxyToServerConnection.create(
        mockProxyServer,
        mockClientConnection,
        "localhost:8080",
        mockFilters,
        null,
        mockTrafficHandler);
  }

  private ProxyToServerConnection createConnectionWithPool(
      ServerConnectionPool pool, List<ActivityTracker> trackers) throws Exception {
    when(mockProxyServer.getActivityTrackers()).thenReturn(trackers);
    return ProxyToServerConnection.createForPool(
        mockProxyServer,
        pool,
        mockClientConnection,
        "localhost:8080",
        null,
        mockFilters,
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        mockTrafficHandler);
  }

  // ============================================================
  // Bug 1: releaseToPool() must exist and release back to pool
  // ============================================================

  @Test
  @DisplayName("releaseToPool should exist and call pool.releaseConnection")
  void releaseToPoolShouldReleaseBackToPool() throws Exception {
    ServerConnectionPool mockPool = mock();
    ProxyToServerConnection conn = createConnectionWithPool(mockPool, Collections.emptyList());
    assertThat(conn).isNotNull();

    conn.setCurrentClientConnectionForRequest(mockClientConnection);
    conn.releaseToPool();

    verify(mockPool).releaseConnection(conn);
  }

  @Test
  @DisplayName("releaseToPool should be no-op when no pool is set")
  void releaseToPoolShouldBeNoopWithoutPool() throws Exception {
    ProxyToServerConnection conn = createConnection(Collections.emptyList());
    assertThat(conn).isNotNull();

    conn.releaseToPool();
  }

  // ============================================================
  // Bug 2: clientConnected should fire before requestReceivedFromClient
  //
  // Tests the Netty pipeline ordering by creating a real
  // ClientToProxyConnection with EmbeddedChannel and sending an HTTP request.
  // ============================================================

  @Test
  @DisplayName(
      "clientConnected should fire before requestReceivedFromClient when request arrives without PROXY header")
  void clientConnectedShouldFireBeforeRequestReceived() throws Exception {
    when(mockProxyServer.getFiltersSource())
        .thenReturn(
            new org.littleshoot.proxy.HttpFiltersSource() {
              @Override
              public int getMaximumRequestBufferSizeInBytes() {
                return 0;
              }

              @Override
              public int getMaximumResponseBufferSizeInBytes() {
                return 0;
              }

              @Override
              public org.littleshoot.proxy.HttpFilters filterRequest(
                  io.netty.handler.codec.http.HttpRequest httpRequest,
                  io.netty.channel.ChannelHandlerContext ctx) {
                return null;
              }
            });
    when(mockProxyServer.getChainProxyManager())
        .thenReturn(mock(org.littleshoot.proxy.ChainedProxyManager.class));
    when(mockProxyServer.getMaxInitialLineLength()).thenReturn(8192);
    when(mockProxyServer.getMaxHeaderSize()).thenReturn(16384);
    when(mockProxyServer.getMaxChunkSize()).thenReturn(16384);
    when(mockProxyServer.getIdleConnectionTimeout()).thenReturn(0);
    when(mockProxyServer.isAcceptProxyProtocol()).thenReturn(false);
    when(mockProxyServer.getProxyAlias()).thenReturn("test-proxy");
    when(mockProxyServer.isAllowRequestsToOriginServer()).thenReturn(true);

    List<String> eventOrder = new ArrayList<>();
    ActivityTracker tracker =
        new ActivityTrackerAdapter() {
          @Override
          public void clientConnected(FlowContext flowContext) {
            eventOrder.add("clientConnected");
          }

          @Override
          public void requestReceivedFromClient(FlowContext flowContext, HttpRequest httpRequest) {
            eventOrder.add("requestReceivedFromClient");
          }
        };
    when(mockProxyServer.getActivityTrackers()).thenReturn(Collections.singletonList(tracker));

    EmbeddedChannel channel = new EmbeddedChannel();
    ClientToProxyConnection clientConn =
        new ClientToProxyConnection(
            mockProxyServer, null, false, channel.pipeline(), mockTrafficHandler);

    DefaultHttpRequest request =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://example.com/");
    channel.writeInbound(request);

    assertThat(eventOrder)
        .as(
            "clientConnected should fire before requestReceivedFromClient for non-PROXY connections")
        .containsSequence("clientConnected", "requestReceivedFromClient");

    channel.finish();
  }

  // ============================================================
  // Bug 3: recordServerConnected/disconnected should use getClientConnection()
  // ============================================================

  @Test
  @DisplayName(
      "recordServerConnected should use getClientConnection() flowContext, not constructor client")
  void recordServerConnectedShouldUseCurrentClient() throws Exception {
    ClientToProxyConnection mockClient2 = mock();
    FullFlowContext mockFlowContext2Full = mock();
    when(mockClient2.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mockFlowContext2Full);

    ActivityTracker tracker = mock(ActivityTracker.class);
    ProxyToServerConnection conn =
        createConnectionWithPool(mock(), Collections.singletonList(tracker));
    assertThat(conn).isNotNull();

    conn.setCurrentClientConnectionForRequest(mockClient2);
    conn.recordServerConnected();

    verify(tracker).serverConnected(eq(mockFlowContext2Full), any(InetSocketAddress.class));
  }

  @Test
  @DisplayName(
      "recordServerDisconnected should use getClientConnection() and clear via the correct client")
  void recordServerDisconnectedShouldUseCurrentClient() throws Exception {
    ClientToProxyConnection mockClient2 = mock();
    FullFlowContext mockFlowContext2Full = mock();
    when(mockClient2.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mockFlowContext2Full);

    ActivityTracker tracker = mock(ActivityTracker.class);
    ProxyToServerConnection conn =
        createConnectionWithPool(mock(), Collections.singletonList(tracker));
    assertThat(conn).isNotNull();

    conn.setCurrentClientConnectionForRequest(mockClient2);
    conn.recordServerDisconnected();

    verify(tracker).serverDisconnected(eq(mockFlowContext2Full), any(InetSocketAddress.class));
    verify(mockClient2).clearFlowContextForServerConnection(conn);
  }

  // ============================================================
  // Bug 5: markResponseComplete must dequeue and wire next pipelined request
  // ============================================================

  @Test
  @DisplayName("markResponseComplete should dequeue pending request and keep connection in use")
  void markResponseCompleteShouldDequeueAndWireNextPending() throws Exception {
    ServerConnectionPool mockPool = mock();
    ProxyToServerConnection conn = createConnectionWithPool(mockPool, Collections.emptyList());
    EmbeddedChannel ch = new EmbeddedChannel();
    conn.channel = ch;

    HttpRequest pendingReq = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/next");
    ClientToProxyConnection pendingClient = mock();
    when(pendingClient.flowContext()).thenReturn(mock());
    when(pendingClient.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mock());
    HttpFilters pendingFilters = mock();
    PendingRequest pending = new PendingRequest(pendingClient, pendingReq, pendingFilters);

    when(mockPool.removePendingRequest(ch)).thenReturn(pending);

    invokeMarkResponseComplete(conn);

    assertThat(getField(conn, "currentHttpRequest")).isSameAs(pendingReq);
    assertThat(getField(conn, "currentClientConnectionForRequest")).isSameAs(pendingClient);
    assertThat(getField(conn, "currentFilters")).isSameAs(pendingFilters);
    assertThat(getField(conn, "currentHttpResponse")).isNull();

    verify(mockPool, never()).releaseConnection(conn);
    verify(mockPool, never()).peekPendingRequest(any());
  }

  @Test
  @DisplayName(
      "markResponseComplete should release connection to pool when no pending requests remain")
  void markResponseCompleteShouldReleaseToPoolWhenNoPending() throws Exception {
    ServerConnectionPool mockPool = mock();
    ProxyToServerConnection conn = createConnectionWithPool(mockPool, Collections.emptyList());
    EmbeddedChannel ch = new EmbeddedChannel();
    conn.channel = ch;

    when(mockPool.removePendingRequest(ch)).thenReturn(null);

    invokeMarkResponseComplete(conn);

    assertThat(getField(conn, "currentHttpRequest")).isNull();
    assertThat(getField(conn, "currentHttpResponse")).isNull();

    verify(mockPool).releaseConnection(conn);
  }

  private static void invokeMarkResponseComplete(ProxyToServerConnection conn) throws Exception {
    Method m = ProxyToServerConnection.class.getDeclaredMethod("markResponseComplete");
    m.setAccessible(true);
    m.invoke(conn);
  }

  private static Object getField(Object obj, String name) throws Exception {
    Class<?> clazz = obj.getClass();
    while (clazz != null) {
      try {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name + " in " + obj.getClass().getName());
  }

  // ============================================================
  // Bug 4: SendProxyProtocolHeader always uses TCP4
  //
  // Tests that HAProxyMessageEncoder correctly encodes TCP6 headers,
  // and that SendProxyProtocolHeader selects the right protocol.
  // ============================================================

  @Test
  @DisplayName("HAProxyMessageEncoder should produce valid PROXY TCP4 header for IPv4 addresses")
  void encoderShouldProduceValidTcp4Header() throws Exception {
    ProxyProtocolMessage msg =
        new ProxyProtocolMessage(
            io.netty.handler.codec.haproxy.HAProxyProtocolVersion.V1,
            io.netty.handler.codec.haproxy.HAProxyCommand.PROXY,
            HAProxyProxiedProtocol.TCP4,
            "192.168.1.1",
            "10.0.0.1",
            12345,
            443);

    EmbeddedChannel ch = new EmbeddedChannel(new HAProxyMessageEncoder());
    ch.writeOutbound(msg);
    ByteBuf out = ch.readOutbound();
    String header = out.toString(io.netty.util.CharsetUtil.US_ASCII);
    out.release();
    ch.finish();

    assertThat(header).startsWith("PROXY TCP4 192.168.1.1 10.0.0.1 12345 443\r\n");
  }

  @Test
  @DisplayName("HAProxyMessageEncoder should produce valid PROXY TCP6 header for IPv6 addresses")
  void encoderShouldProduceValidTcp6Header() throws Exception {
    ProxyProtocolMessage msg =
        new ProxyProtocolMessage(
            io.netty.handler.codec.haproxy.HAProxyProtocolVersion.V1,
            io.netty.handler.codec.haproxy.HAProxyCommand.PROXY,
            HAProxyProxiedProtocol.TCP6,
            "2001:db8::1",
            "2001:db8::2",
            12345,
            443);

    EmbeddedChannel ch = new EmbeddedChannel(new HAProxyMessageEncoder());
    ch.writeOutbound(msg);
    ByteBuf out = ch.readOutbound();
    String header = out.toString(io.netty.util.CharsetUtil.US_ASCII);
    out.release();
    ch.finish();

    assertThat(header).startsWith("PROXY TCP6 2001:db8::1 2001:db8::2 12345 443\r\n");
  }

  @Test
  @DisplayName("SendProxyProtocolHeader should select TCP6 when both client and server are IPv6")
  void sendProxyProtocolHeaderShouldSelectTcp6ForIpv6() throws Exception {
    ServerConnectionPool mockPool = mock();
    ProxyToServerConnection conn = createConnectionWithPool(mockPool, Collections.emptyList());
    assertThat(conn).isNotNull();

    InetSocketAddress ipv6ClientAddr = new InetSocketAddress("2001:db8::1", 12345);
    InetSocketAddress ipv6RemoteAddr = new InetSocketAddress("2001:db8::2", 443);
    when(mockClientConnection.getHaProxyMessage()).thenReturn(null);
    when(mockClientConnection.getClientAddress()).thenReturn(ipv6ClientAddr);

    conn.setRemoteAddress(ipv6RemoteAddr);

    EmbeddedChannel channel = new EmbeddedChannel(new HAProxyMessageEncoder());
    conn.channel = channel;

    conn.SendProxyProtocolHeader.execute();

    ByteBuf out = channel.readOutbound();
    assertThat(out).as("PROXY protocol header should be written to the channel").isNotNull();
    String header = out.toString(io.netty.util.CharsetUtil.US_ASCII);
    out.release();
    channel.finish();

    assertThat(header)
        .as("IPv6 addresses should produce a TCP6 PROXY protocol header")
        .startsWith("PROXY TCP6 2001:db8:0:0:0:0:0:1 2001:db8:0:0:0:0:0:2 12345 443\r\n");
  }
}
