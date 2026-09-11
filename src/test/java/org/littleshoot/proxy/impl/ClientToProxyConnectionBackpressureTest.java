package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpFiltersSource;

class ClientToProxyConnectionBackpressureTest {

  private DefaultHttpProxyServer mockProxyServer;
  private GlobalTrafficShapingHandler mockTrafficHandler;
  private ClientToProxyConnection clientConn;
  private EmbeddedChannel clientChannel;

  private ProxyToServerConnection mockServerInMap;
  private ProxyToServerConnection mockCurrentServer;

  @BeforeEach
  void setUp() throws Exception {
    mockProxyServer = mock();
    mockTrafficHandler = mock();
    when(mockProxyServer.getChainProxyManager()).thenReturn(mock(ChainedProxyManager.class));
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

    // Create mock server connections
    mockServerInMap = createServerConnectionMock();
    mockCurrentServer = createServerConnectionMock();

    // Populate serverConnectionsByHostAndPort (private field, set via reflection since
    // there is no public add method — connections are added internally during request flow)
    @SuppressWarnings("unchecked")
    ConcurrentMap<String, ProxyToServerConnection> serverMap =
        (ConcurrentMap<String, ProxyToServerConnection>)
            field(ClientToProxyConnection.class, "serverConnectionsByHostAndPort").get(clientConn);
    serverMap.put("example.com:80", mockServerInMap);

    // Set currentServerConnection (private field, no public setter available)
    field(ClientToProxyConnection.class, "currentServerConnection")
        .set(clientConn, mockCurrentServer);
  }

  /**
   * Creates a mock ProxyToServerConnection with a proper channel so stopReading/resumeReading work.
   */
  private static ProxyToServerConnection createServerConnectionMock() throws Exception {
    ProxyToServerConnection conn = mock();
    Channel ch = mock();
    ChannelConfig cfg = mock();
    when(ch.config()).thenReturn(cfg);
    field(ProxyConnection.class, "channel").set(conn, ch);
    return conn;
  }

  // -----------------------------------------------------------------------
  // becameSaturated
  // -----------------------------------------------------------------------

  @Test
  @DisplayName(
      "becameSaturated should stop reading on currentServerConnection when client is saturated")
  void becameSaturatedShouldStopReadingOnCurrentServerConnection() throws Exception {
    mockClientChannelNotWritable();
    clientConn.becameSaturated();
    verify(mockCurrentServer).stopReading();
  }

  @Test
  @DisplayName(
      "becameSaturated should stop reading on mapped server connections when client is saturated")
  void becameSaturatedShouldStopReadingOnAllServerConnections() throws Exception {
    mockClientChannelNotWritable();
    clientConn.becameSaturated();
    verify(mockServerInMap).stopReading();
  }

  @Test
  @DisplayName("becameSaturated should NOT stop reading when client is not saturated")
  void becameSaturatedShouldNotStopReadingWhenNotSaturated() {
    // clientChannel is writable by default → isSaturated() returns false
    clientConn.becameSaturated();
    verify(mockCurrentServer, never()).stopReading();
    verify(mockServerInMap, never()).stopReading();
  }

  @Test
  @DisplayName("becameSaturated should handle null currentServerConnection (pooled connection)")
  void becameSaturatedShouldHandleNullCurrentServerConnection() throws Exception {
    field(ClientToProxyConnection.class, "currentServerConnection").set(clientConn, null);
    mockClientChannelNotWritable();
    clientConn.becameSaturated();
    // Should not throw NPE; the mapped connection should still be stopped
    verify(mockServerInMap).stopReading();
  }

  // -----------------------------------------------------------------------
  // becameWritable
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("becameWritable should resume reading on currentServerConnection")
  void becameWritableShouldResumeReadingOnCurrentServerConnection() throws Exception {
    clientConn.becameWritable();
    verify(mockCurrentServer).resumeReading();
  }

  @Test
  @DisplayName("becameWritable should resume reading on mapped server connections")
  void becameWritableShouldResumeReadingOnMappedConnections() throws Exception {
    clientConn.becameWritable();
    verify(mockServerInMap).resumeReading();
  }

  @Test
  @DisplayName("becameWritable should NOT resume reading when client is still saturated")
  void becameWritableShouldNotResumeReadingWhenSaturated() throws Exception {
    mockClientChannelNotWritable();
    clientConn.becameWritable();
    verify(mockCurrentServer, never()).resumeReading();
    verify(mockServerInMap, never()).resumeReading();
  }

  @Test
  @DisplayName("becameWritable should handle null currentServerConnection")
  void becameWritableShouldHandleNullCurrentServerConnection() throws Exception {
    field(ClientToProxyConnection.class, "currentServerConnection").set(clientConn, null);
    clientConn.becameWritable();
    verify(mockServerInMap).resumeReading();
  }

  // -----------------------------------------------------------------------
  // serverBecameSaturated
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("serverBecameSaturated should stop client reading when server is saturated")
  void serverBecameSaturatedShouldStopClientWhenServerSaturated() {
    clientChannel.config().setAutoRead(true);
    when(mockCurrentServer.isSaturated()).thenReturn(true);

    clientConn.serverBecameSaturated(mockCurrentServer);

    assertThat(clientChannel.config().isAutoRead()).isFalse();
  }

  @Test
  @DisplayName("serverBecameSaturated should not stop client reading when server is not saturated")
  void serverBecameSaturatedShouldNotStopClientWhenServerNotSaturated() {
    clientChannel.config().setAutoRead(true);
    when(mockCurrentServer.isSaturated()).thenReturn(false);

    clientConn.serverBecameSaturated(mockCurrentServer);

    assertThat(clientChannel.config().isAutoRead()).isTrue();
  }

  // -----------------------------------------------------------------------
  // serverBecameWriteable
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("serverBecameWriteable should resume client reading when no servers are saturated")
  void serverBecameWriteableShouldResumeWhenNoServerSaturated() {
    clientChannel.config().setAutoRead(false);
    when(mockServerInMap.isSaturated()).thenReturn(false);
    when(mockCurrentServer.isSaturated()).thenReturn(false);

    clientConn.serverBecameWriteable(mockCurrentServer);

    assertThat(clientChannel.config().isAutoRead()).isTrue();
  }

  @Test
  @DisplayName("serverBecameWriteable should not resume when a mapped server is still saturated")
  void serverBecameWriteableShouldNotResumeWhenMappedSaturated() {
    clientChannel.config().setAutoRead(false);
    when(mockServerInMap.isSaturated()).thenReturn(true);
    when(mockCurrentServer.isSaturated()).thenReturn(false);

    clientConn.serverBecameWriteable(mockCurrentServer);

    assertThat(clientChannel.config().isAutoRead()).isFalse();
  }

  @Test
  @DisplayName(
      "serverBecameWriteable should check currentServerConnection when "
          + "it is a different connection than the one that became writeable")
  void serverBecameWriteableShouldCheckCurrentServerConnection() throws Exception {
    clientChannel.config().setAutoRead(false);
    // The connection that became writeable is NOT currentServerConnection
    ProxyToServerConnection differentServer = createServerConnectionMock();
    when(mockServerInMap.isSaturated()).thenReturn(false);
    when(mockCurrentServer.isSaturated()).thenReturn(true);

    clientConn.serverBecameWriteable(differentServer);

    // Should NOT resume because currentServerConnection is still saturated
    assertThat(clientChannel.config().isAutoRead()).isFalse();
  }

  @Test
  @DisplayName("serverBecameWriteable should resume when currentServerConnection is the source")
  void serverBecameWriteableShouldResumeWhenCurrentIsSource() {
    clientChannel.config().setAutoRead(false);
    when(mockServerInMap.isSaturated()).thenReturn(false);
    when(mockCurrentServer.isSaturated()).thenReturn(false);

    clientConn.serverBecameWriteable(mockCurrentServer);

    assertThat(clientChannel.config().isAutoRead()).isTrue();
  }

  @Test
  @DisplayName("serverBecameWriteable should handle null currentServerConnection")
  void serverBecameWriteableShouldHandleNullCurrentServerConnection() throws Exception {
    clientChannel.config().setAutoRead(false);
    field(ClientToProxyConnection.class, "currentServerConnection").set(clientConn, null);
    when(mockServerInMap.isSaturated()).thenReturn(false);

    clientConn.serverBecameWriteable(mockCurrentServer);

    assertThat(clientChannel.config().isAutoRead()).isTrue();
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /**
   * Replaces the client channel with a non-writable mock to make isSaturated() return true. The
   * EmbeddedChannel always reports isWritable() = true, so a mock is required here.
   */
  private void mockClientChannelNotWritable() throws Exception {
    Channel ch = mock();
    ChannelConfig cfg = mock();
    when(ch.isWritable()).thenReturn(false);
    when(ch.config()).thenReturn(cfg);
    field(ProxyConnection.class, "channel").set(clientConn, ch);
  }

  private static Field field(Class<?> clazz, String name) throws NoSuchFieldException {
    Class<?> current = clazz;
    while (current != null) {
      try {
        Field f = current.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name + " in " + clazz.getName());
  }
}
