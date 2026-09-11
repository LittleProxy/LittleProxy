package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.HttpFiltersSource;

class ClientToProxyConnectionTest {

  private final DefaultHttpProxyServer proxyServer = mock();

  private ClientToProxyConnection createConnection(ActivityTracker... trackers) {
    when(proxyServer.getMaxInitialLineLength()).thenReturn(4096);
    when(proxyServer.getMaxHeaderSize()).thenReturn(8192);
    when(proxyServer.getMaxChunkSize()).thenReturn(8192);
    when(proxyServer.getIdleConnectionTimeout()).thenReturn(60);
    when(proxyServer.getFiltersSource()).thenReturn(mock(HttpFiltersSource.class));
    when(proxyServer.getActivityTrackers()).thenReturn(List.of(trackers));

    return new ClientToProxyConnection(proxyServer, null, false, mock(ChannelPipeline.class), null);
  }

  @Test
  @DisplayName("disconnected should notify all trackers even if one throws")
  void disconnectedShouldNotifyAllTrackersEvenIfOneThrows() {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .clientDisconnected(any(), any());
    ActivityTracker normalTracker = mock();
    ClientToProxyConnection connection = createConnection(throwingTracker, normalTracker);

    connection.disconnected();

    verify(throwingTracker).clientDisconnected(connection.flowContext(), null);
    verify(normalTracker).clientDisconnected(connection.flowContext(), null);
  }

  @Test
  @DisplayName("disconnected should notify trackers when no exception occurs")
  void disconnectedShouldNotifyTrackersWhenNoException() {
    ActivityTracker normalTracker = mock();
    ClientToProxyConnection connection = createConnection(normalTracker);

    connection.disconnected();

    verify(normalTracker).clientDisconnected(connection.flowContext(), null);
  }

  @Test
  @DisplayName("encrypt requires a client certificate when authenticateClients is true")
  void encryptRequiresClientCertificateWhenAuthenticateClientsIsTrue() throws Exception {
    ClientToProxyConnection connection = createConnection();
    SSLEngine engine = newServerEngine();

    connection.encrypt(newRealPipeline(), engine, true);

    assertThat(engine.getNeedClientAuth()).isTrue();
  }

  @Test
  @DisplayName("encrypt leaves a plain engine unauthenticated when authenticateClients is false")
  void encryptLeavesPlainEngineUnauthenticatedWhenAuthenticateClientsIsFalse() throws Exception {
    ClientToProxyConnection connection = createConnection();
    SSLEngine engine = newServerEngine(); // default engine: neither need nor want

    connection.encrypt(newRealPipeline(), engine, false);

    assertThat(engine.getNeedClientAuth()).isFalse();
    assertThat(engine.getWantClientAuth()).isFalse();
  }

  @Test
  @DisplayName(
      "encrypt preserves setWantClientAuth from the SslEngineSource when authenticateClients is false")
  void encryptPreservesWantClientAuthWhenAuthenticateClientsIsFalse() throws Exception {
    ClientToProxyConnection connection = createConnection();
    SSLEngine engine = newServerEngine();
    engine.setWantClientAuth(true); // e.g. an engine built with Netty ClientAuth.OPTIONAL

    connection.encrypt(newRealPipeline(), engine, false);

    // Before the fix, encrypt() called setNeedClientAuth(false) here, which cleared this flag.
    assertThat(engine.getWantClientAuth()).isTrue();
    assertThat(engine.getNeedClientAuth()).isFalse();
  }

  private static SSLEngine newServerEngine() throws Exception {
    return SSLContext.getDefault().createSSLEngine();
  }

  private static ChannelPipeline newRealPipeline() {
    return new EmbeddedChannel().pipeline();
  }
}
