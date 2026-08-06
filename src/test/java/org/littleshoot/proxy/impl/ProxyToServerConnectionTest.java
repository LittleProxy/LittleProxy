package org.littleshoot.proxy.impl;

import io.netty.handler.traffic.GlobalTrafficShapingHandler;
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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLProtocolException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Queue;

import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
  private ProxyToServerConnection createConnection(ActivityTracker... trackers) throws UnknownHostException {
    when(proxyServer.getActivityTrackers()).thenReturn(List.of(trackers));
    return requireNonNull(ProxyToServerConnection.create(
        proxyServer,
        clientConnection,
        "localhost:8080",
        filters,
        null,
        trafficHandler));
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

      assertThat(connection.shouldRetryWithoutSsl(new SSLHandshakeException(errorMessage))).isTrue();
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
