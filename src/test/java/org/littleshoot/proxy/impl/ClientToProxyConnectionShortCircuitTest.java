package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HttpFiltersSource;

class ClientToProxyConnectionShortCircuitTest {

  private DefaultHttpProxyServer mockProxyServer;
  private ClientToProxyConnection clientConn;
  private EmbeddedChannel clientChannel;
  private GlobalTrafficShapingHandler mockTrafficHandler;

  @BeforeEach
  void setUp() throws Exception {
    mockProxyServer = mock();
    mockTrafficHandler = mock();

    when(mockProxyServer.getChainProxyManager()).thenReturn(null);
    when(mockProxyServer.getServerResolver())
        .thenReturn(mock(org.littleshoot.proxy.HostResolver.class));
    when(mockProxyServer.getServerResolver().resolve(anyString(), anyInt()))
        .thenReturn(new InetSocketAddress("127.0.0.1", 8080));
    when(mockProxyServer.getFiltersSource()).thenReturn(mock(HttpFiltersSource.class));
    when(mockProxyServer.getMaxInitialLineLength()).thenReturn(8192);
    when(mockProxyServer.getMaxHeaderSize()).thenReturn(16384);
    when(mockProxyServer.getMaxChunkSize()).thenReturn(16384);
    when(mockProxyServer.getIdleConnectionTimeout()).thenReturn(0);
    when(mockProxyServer.isAcceptProxyProtocol()).thenReturn(false);
    when(mockProxyServer.getProxyAlias()).thenReturn("test");
    when(mockProxyServer.isAllowRequestsToOriginServer()).thenReturn(true);
    when(mockProxyServer.getActivityTrackers()).thenReturn(java.util.Collections.emptyList());

    clientChannel = new EmbeddedChannel();
    clientConn =
        new ClientToProxyConnection(
            mockProxyServer, null, false, clientChannel.pipeline(), mockTrafficHandler);
  }

  // -----------------------------------------------------------------------
  // setCurrentClientConnectionForRequest(null) — covers the short-circuit path
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("setCurrentClientConnectionForRequest(null) should be callable")
  void setCurrentClientConnectionForRequestShouldAcceptNull() throws Exception {
    // Create a real ProxyToServerConnection to test the setter on
    ProxyToServerConnection conn = createRealPooledConnection();

    // This is the exact call made at ClientToProxyConnection line 393 when
    // proxyToServerRequest short-circuits with a shared pool
    conn.setCurrentClientConnectionForRequest(null);

    // Verify the field was set to null
    Field field =
        ProxyToServerConnection.class.getDeclaredField("currentClientConnectionForRequest");
    field.setAccessible(true);
    assertThat(field.get(conn)).isNull();
  }

  @Test
  @DisplayName("releaseToPool after setCurrentClientConnectionForRequest(null) should not throw")
  void releaseToPoolAfterNullShouldNotThrow() throws Exception {
    ProxyToServerConnection conn = createRealPooledConnection();

    // Same sequence as ClientToProxyConnection lines 392-395
    conn.setCurrentClientConnectionForRequest(null);
    conn.releaseToPool();

    // Verify the connection was released back (currentHttpRequest cleared)
    Field reqField = ProxyToServerConnection.class.getDeclaredField("currentHttpRequest");
    reqField.setAccessible(true);
    assertThat(reqField.get(conn)).isNull();

    Field clientField =
        ProxyToServerConnection.class.getDeclaredField("currentClientConnectionForRequest");
    clientField.setAccessible(true);
    assertThat(clientField.get(conn)).isNull();
  }

  // -----------------------------------------------------------------------
  // getClientAddress null-safety
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("getClientAddress should return null when channel is null")
  void getClientAddressShouldBeNullWhenChannelNull() throws Exception {
    setField(clientConn, "channel", null);
    assertThat(clientConn.getClientAddress()).isNull();
  }

  @Test
  @DisplayName("getClientAddress should return null for non-InetSocketAddress remote address")
  void getClientAddressShouldBeNullForNonInetSocketAddress() {
    // EmbeddedSocketAddress is NOT an InetSocketAddress
    assertThat(clientConn.getClientAddress()).isNull();
  }

  @Test
  @DisplayName("getClientAddress should return InetSocketAddress when present")
  void getClientAddressShouldReturnInetSocketAddress() throws Exception {
    Channel ch = mock();
    when(ch.remoteAddress()).thenReturn(new InetSocketAddress("192.168.1.1", 12345));
    setField(clientConn, "channel", ch);

    InetSocketAddress addr = clientConn.getClientAddress();
    assertThat(addr).isNotNull();
    assertThat(addr.getHostString()).isEqualTo("192.168.1.1");
    assertThat(addr.getPort()).isEqualTo(12345);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /** Creates a minimal ProxyToServerConnection to test setter calls on. */
  private ProxyToServerConnection createRealPooledConnection() throws Exception {
    ClientToProxyConnection mockClient = mock();
    when(mockClient.flowContext()).thenReturn(mock());
    when(mockClient.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mock());
    return ProxyToServerConnection.createForPool(
        mockProxyServer,
        mock(ServerConnectionPool.class),
        mockClient,
        "example.com:80",
        null,
        mock(),
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        mockTrafficHandler);
  }

  private static void setField(Object obj, String name, Object value) throws Exception {
    Field f = findField(obj.getClass(), name);
    f.setAccessible(true);
    f.set(obj, value);
  }

  private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
    try {
      return clazz.getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      if (clazz.getSuperclass() != null) {
        return findField(clazz.getSuperclass(), name);
      }
      throw e;
    }
  }
}
