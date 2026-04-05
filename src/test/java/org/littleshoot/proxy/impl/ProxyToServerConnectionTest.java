package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;

class ProxyToServerConnectionTest {

  @Test
  @DisplayName("disconnected should clear flow context even when ActivityTracker throws exception")
  void disconnectedShouldClearFlowContextEvenWhenActivityTrackerThrowsException() throws Exception {
    // given
    DefaultHttpProxyServer mockProxyServer = mock();
    ClientToProxyConnection mockClientConnection = mock();
    HttpFilters mockFilters = mock();
    GlobalTrafficShapingHandler mockTrafficHandler = mock();
    HostResolver mockHostResolver = mock();

    when(mockProxyServer.getServerResolver()).thenReturn(mockHostResolver);
    when(mockHostResolver.resolve(any(), anyInt()))
        .thenReturn(new InetSocketAddress("127.0.0.1", 8080));

    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .serverDisconnected(any(), any());

    List<ActivityTracker> trackers = Collections.singletonList(throwingTracker);
    when(mockProxyServer.getActivityTrackers()).thenReturn(trackers);

    FlowContext mockClientFlowContext = mock();
    when(mockClientConnection.flowContext()).thenReturn(mockClientFlowContext);
    FullFlowContext mockFlowContext = mock();
    when(mockClientConnection.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mockFlowContext);

    ProxyToServerConnection connection =
        ProxyToServerConnection.create(
            mockProxyServer,
            mockClientConnection,
            "localhost:8080",
            mockFilters,
            null,
            mockTrafficHandler);

    assertThat(connection).isNotNull();
    // when
    // recordServerDisconnected which is called in disconnected, clear FlowContext
    connection.disconnected();

    // then
    verify(mockClientConnection).clearFlowContextForServerConnection(connection);
  }

  @Test
  @DisplayName("disconnected should clear flow context when no exception occurs")
  void disconnectedShouldClearFlowContextWhenNoException() throws Exception {
    DefaultHttpProxyServer mockProxyServer = mock();
    ClientToProxyConnection mockClientConnection = mock();
    HttpFilters mockFilters = mock();
    GlobalTrafficShapingHandler mockTrafficHandler = mock();
    HostResolver mockHostResolver = mock();

    when(mockProxyServer.getServerResolver()).thenReturn(mockHostResolver);
    when(mockHostResolver.resolve(any(), anyInt()))
        .thenReturn(new InetSocketAddress("127.0.0.1", 8080));

    ActivityTracker normalTracker = mock();
    List<ActivityTracker> trackers = Collections.singletonList(normalTracker);
    when(mockProxyServer.getActivityTrackers()).thenReturn(trackers);

    FlowContext mockClientFlowContext = mock();
    when(mockClientConnection.flowContext()).thenReturn(mockClientFlowContext);
    FullFlowContext mockFlowContext = mock();
    when(mockClientConnection.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mockFlowContext);

    ProxyToServerConnection connection =
        ProxyToServerConnection.create(
            mockProxyServer,
            mockClientConnection,
            "localhost:8080",
            mockFilters,
            null,
            mockTrafficHandler);

    assertThat(connection).isNotNull();

    connection.disconnected();

    verify(normalTracker).serverDisconnected(any(), any());
    verify(mockClientConnection).clearFlowContextForServerConnection(connection);
  }
}
