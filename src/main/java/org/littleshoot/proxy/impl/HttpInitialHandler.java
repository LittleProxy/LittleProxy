package org.littleshoot.proxy.impl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpObject;

public class HttpInitialHandler<T extends HttpObject> extends ChannelInboundHandlerAdapter {

  private final ProxyConnection<T> proxyConnection;

  HttpInitialHandler(ProxyConnection<T> proxyConnection) {
    this.proxyConnection = proxyConnection;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    final ConnectionState connectionState = proxyConnection.readHTTPInitial((T)msg);
    proxyConnection.become(connectionState);
  }

  @Override
  public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    proxyConnection.exceptionCaught(cause);
  }
}
