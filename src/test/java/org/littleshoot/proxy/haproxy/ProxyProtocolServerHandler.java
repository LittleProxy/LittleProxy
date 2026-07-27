package org.littleshoot.proxy.haproxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ProxyProtocolServerHandler extends ChannelInboundHandlerAdapter {

  private final CountDownLatch messageLatch = new CountDownLatch(1);
  private volatile HAProxyMessage haProxyMessage;

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof HAProxyMessage) {
      haProxyMessage = (HAProxyMessage) msg;
      messageLatch.countDown();
    }
  }

  /**
   * Waits up to the given timeout for an HAProxyMessage to arrive. Returns the message, or null if
   * none arrived within the timeout.
   */
  HAProxyMessage awaitHaProxyMessage(long timeout, TimeUnit unit) throws InterruptedException {
    messageLatch.await(timeout, unit);
    return haProxyMessage;
  }
}
