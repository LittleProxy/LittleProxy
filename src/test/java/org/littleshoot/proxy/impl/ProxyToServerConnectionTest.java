package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxy;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.ChainedProxyType;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.TransportProtocol;

class ProxyToServerConnectionTest {

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
      throws UnknownHostException {
    when(mockProxyServer.getActivityTrackers()).thenReturn(trackers);
    return ProxyToServerConnection.create(
        mockProxyServer,
        mockClientConnection,
        "localhost:8080",
        mockFilters,
        null,
        mockTrafficHandler);
  }

  @Test
  @DisplayName("disconnected should clear flow context even when ActivityTracker throws exception")
  void disconnectedShouldClearFlowContextEvenWhenActivityTrackerThrowsException() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .serverDisconnected(any(), any());

    List<ActivityTracker> trackers = Collections.singletonList(throwingTracker);
    ProxyToServerConnection connection = createConnection(trackers);
    assertThat(connection).isNotNull();

    connection.disconnected();

    verify(throwingTracker)
        .serverDisconnected(any(FullFlowContext.class), any(InetSocketAddress.class));
    verify(mockClientConnection).clearFlowContextForServerConnection(connection);
  }

  @Test
  @DisplayName("disconnected should clear flow context when no exception occurs")
  void disconnectedShouldClearFlowContextWhenNoException() throws Exception {
    ActivityTracker normalTracker = mock();
    List<ActivityTracker> trackers = Collections.singletonList(normalTracker);
    ProxyToServerConnection connection = createConnection(trackers);
    assertThat(connection).isNotNull();

    connection.disconnected();

    verify(normalTracker)
        .serverDisconnected(any(FullFlowContext.class), any(InetSocketAddress.class));
    verify(mockClientConnection).clearFlowContextForServerConnection(connection);
  }

  @Test
  @DisplayName("serverConnected should notify all trackers even if one throws")
  void serverConnectedShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .serverConnected(any(), any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    connection.recordServerConnected();

    verify(throwingTracker).serverConnected(any(), any());
    verify(succeedingTracker).serverConnected(any(), any());
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

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    connection.recordServerDisconnected();

    verify(throwingTracker).serverDisconnected(any(), any());
    verify(succeedingTracker).serverDisconnected(any(), any());
    verify(mockClientConnection).clearFlowContextForServerConnection(connection);
  }

  @Test
  @DisplayName("connectionSaturated should notify all trackers even if one throws")
  void connectionSaturatedShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .connectionSaturated(any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    connection.recordConnectionSaturated();

    verify(throwingTracker).connectionSaturated(any());
    verify(succeedingTracker).connectionSaturated(any());
  }

  @Test
  @DisplayName("connectionWritable should notify all trackers even if one throws")
  void connectionWritableShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception")).when(throwingTracker).connectionWritable(any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    connection.recordConnectionWritable();

    verify(throwingTracker).connectionWritable(any());
    verify(succeedingTracker).connectionWritable(any());
  }

  @Test
  @DisplayName("connectionTimedOut should notify all trackers even if one throws")
  void connectionTimedOutShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception")).when(throwingTracker).connectionTimedOut(any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    connection.recordConnectionTimedOut();

    verify(throwingTracker).connectionTimedOut(any());
    verify(succeedingTracker).connectionTimedOut(any());
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

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    connection.recordConnectionExceptionCaught(new RuntimeException("Test cause"));

    verify(throwingTracker).connectionExceptionCaught(any(), any());
    verify(succeedingTracker).connectionExceptionCaught(any(), any());
  }

  private ProxyToServerConnection createConnectionWithChainedProxy(ChainedProxy chainedProxy)
      throws UnknownHostException {
    ChainedProxyManager chainedProxyManager = mock();
    when(mockProxyServer.getChainProxyManager()).thenReturn(chainedProxyManager);
    doAnswer(
            invocation -> {
              invocation.<Queue<ChainedProxy>>getArgument(1).add(chainedProxy);
              return null;
            })
        .when(chainedProxyManager)
        .lookupChainedProxies(any(), any(), any());

    when(chainedProxy.getTransportProtocol()).thenReturn(TransportProtocol.TCP);
    when(chainedProxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
    when(chainedProxy.getChainedProxyAddress())
        .thenReturn(new InetSocketAddress("127.0.0.1", 9443));

    return createConnection(Collections.emptyList());
  }
}
