package org.littleshoot.proxy.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.ChannelPipeline;
import java.util.List;
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
}
