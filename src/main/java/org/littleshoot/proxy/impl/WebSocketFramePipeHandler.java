package org.littleshoot.proxy.impl;

import static java.util.Objects.requireNonNull;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.function.Supplier;
import org.littleshoot.proxy.HttpFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ChannelInboundHandlerAdapter} that forwards raw WebSocket frame bytes to the peer
 * connection and optionally notifies an {@link HttpFilters} observer via {@link
 * HttpFilters#webSocketFrameReceived(Supplier, boolean)}.
 *
 * <p>Installed on both the client-to-proxy and proxy-to-server channels after a WebSocket upgrade,
 * replacing the HTTP codec pipeline.
 */
public class WebSocketFramePipeHandler extends ChannelInboundHandlerAdapter {
  private static final Logger log = LoggerFactory.getLogger(WebSocketFramePipeHandler.class);
  private final ProxyConnection<?> sink;
  private final HttpFilters filters;
  private final boolean fromClient;

  public WebSocketFramePipeHandler(
      final ProxyConnection<?> sink, final HttpFilters filters, final boolean fromClient) {
    this.sink = requireNonNull(sink, "sink cannot be null");
    this.filters = filters;
    this.fromClient = fromClient;
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
    if (filters != null && msg instanceof ByteBuf) {
      try {
        filters.webSocketFrameReceived(new WebSocketFrameBytes((ByteBuf) msg), fromClient);
      } catch (Exception e) {
        log.error("Failed to notify listeners that websocket frame received", e);
      }
    }
    Channel channel = sink.channel;
    if (channel != null) {
      channel.writeAndFlush(msg);
    }
  }

  @Override
  public void channelInactive(final ChannelHandlerContext ctx) {
    if (!sink.getCurrentState().isDisconnectingOrDisconnected()) {
      sink.disconnect();
    }
  }

  private static class WebSocketFrameBytes implements Supplier<byte[]> {
    private final ByteBuf message;

    private WebSocketFrameBytes(ByteBuf message) {
      this.message = message;
    }

    @Override
    public byte[] get() {
      byte[] frameBytes = new byte[message.readableBytes()];
      message.getBytes(message.readerIndex(), frameBytes);
      return frameBytes;
    }
  }
}
