package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the writability callback that drives backpressure: a writable channel must notify {@link
 * ProxyConnection#becameWritable()} and a saturated one must notify {@link
 * ProxyConnection#becameSaturated()}.
 */
final class ProxyConnectionWritabilityTest {

  private final DefaultHttpProxyServer proxyServer = mock();
  private final AtomicInteger writableCount = new AtomicInteger();
  private final AtomicInteger saturatedCount = new AtomicInteger();

  private final ProxyConnection<HttpRequest> connection =
      new ProxyConnection<>(ConnectionState.AWAITING_INITIAL, proxyServer, false) {
        @Override
        ConnectionState readHTTPInitial(HttpRequest httpRequest) {
          return getCurrentState();
        }

        @Override
        protected void readHTTPChunk(HttpContent chunk) {}

        @Override
        protected void readRaw(ByteBuf buf) {}

        @Override
        protected void readHAProxyMessage(HAProxyMessage msg) {}

        @Override
        protected void becameWritable() {
          writableCount.incrementAndGet();
        }

        @Override
        protected void becameSaturated() {
          saturatedCount.incrementAndGet();
        }
      };

  @Test
  @DisplayName("a writable channel notifies becameWritable")
  void writableChannelNotifiesBecameWritable() throws Exception {
    connection.channel = mock(Channel.class);
    when(connection.channel.isWritable()).thenReturn(true);

    connection.channelWritabilityChanged(mock(ChannelHandlerContext.class));

    assertThat(writableCount).hasValue(1);
    assertThat(saturatedCount).hasValue(0);
  }

  @Test
  @DisplayName("a saturated channel notifies becameSaturated")
  void saturatedChannelNotifiesBecameSaturated() throws Exception {
    connection.channel = mock(Channel.class);
    when(connection.channel.isWritable()).thenReturn(false);

    connection.channelWritabilityChanged(mock(ChannelHandlerContext.class));

    assertThat(writableCount).hasValue(0);
    assertThat(saturatedCount).hasValue(1);
  }
}
