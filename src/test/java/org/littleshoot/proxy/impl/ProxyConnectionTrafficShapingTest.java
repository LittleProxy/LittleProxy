package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.impl.ProxyConnection.TRAFFIC_SHAPING_READ_SUSPENDED;
import static org.littleshoot.proxy.impl.ProxyConnection.isReadSuspendedByTrafficShaping;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpFiltersSource;

class ProxyConnectionTrafficShapingTest {
  private final EmbeddedChannel channel = new EmbeddedChannel();

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  @Test
  void detectsReadSuspensionByNettyTrafficShapingHandler() {
    // 1 byte/second read limit: reading 1000 bytes makes the handler suspend reads
    channel.pipeline().addFirst(new ChannelTrafficShapingHandler(0, 1, 1000));
    assertThat(isReadSuspendedByTrafficShaping(channel)).isFalse();

    channel.writeInbound(Unpooled.wrappedBuffer(new byte[1000]));

    assertThat(channel.config().isAutoRead()).isFalse();
    assertThat(isReadSuspendedByTrafficShaping(channel)).isTrue();
  }

  @Test
  void resumeReadingDoesNotOverrideTrafficShapingSuspension() {
    ClientToProxyConnection connection = createConnection();
    channel.config().setAutoRead(false);
    channel.attr(TRAFFIC_SHAPING_READ_SUSPENDED).set(true);

    connection.resumeReading();

    assertThat(channel.config().isAutoRead()).isFalse();
  }

  @Test
  void resumeReadingWhenNotSuspendedByTrafficShaping() {
    ClientToProxyConnection connection = createConnection();
    channel.config().setAutoRead(false);
    channel.attr(TRAFFIC_SHAPING_READ_SUSPENDED).set(false);

    connection.resumeReading();

    assertThat(channel.config().isAutoRead()).isTrue();
  }

  private ClientToProxyConnection createConnection() {
    DefaultHttpProxyServer proxyServer = mock();
    when(proxyServer.getChainProxyManager()).thenReturn(mock(ChainedProxyManager.class));
    when(proxyServer.getFiltersSource()).thenReturn(mock(HttpFiltersSource.class));
    when(proxyServer.getMaxInitialLineLength()).thenReturn(8192);
    when(proxyServer.getMaxHeaderSize()).thenReturn(16384);
    when(proxyServer.getMaxChunkSize()).thenReturn(16384);
    when(proxyServer.getProxyAlias()).thenReturn("test");
    when(proxyServer.getActivityTrackers()).thenReturn(Collections.emptyList());
    return new ClientToProxyConnection(proxyServer, null, false, channel.pipeline(), mock());
  }
}
