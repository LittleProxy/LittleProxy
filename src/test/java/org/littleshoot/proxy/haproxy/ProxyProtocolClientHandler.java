package org.littleshoot.proxy.haproxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

public class ProxyProtocolClientHandler extends ChannelInboundHandlerAdapter {

  private static final String HOST = "http://localhost";
  private final int serverPort;
  private final ProxyProtocolHeader proxyProtocolHeader;
  private final SslContext clientSslContext;
  private final int proxyPort;
  private final CountDownLatch tlsHandshakeDone;
  private final boolean sendProxyBeforeTls;
  private volatile boolean tlsHandshakeSuccess;
  private volatile Throwable tlsHandshakeFailureCause;

  ProxyProtocolClientHandler(
      int serverPort,
      ProxyProtocolHeader proxyProtocolHeader,
      SslContext clientSslContext,
      int proxyPort,
      CountDownLatch tlsHandshakeDone,
      boolean sendProxyBeforeTls) {
    this.serverPort = serverPort;
    this.proxyProtocolHeader = proxyProtocolHeader;
    this.clientSslContext = clientSslContext;
    this.proxyPort = proxyPort;
    this.tlsHandshakeDone = tlsHandshakeDone;
    this.sendProxyBeforeTls = sendProxyBeforeTls;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    if (clientSslContext != null) {
      if (sendProxyBeforeTls) {
        // Correct order: write PROXY header as raw cleartext bytes, then
        // add SslHandler and send CONNECT once TLS handshake completes.
        sendProxyThenTls(ctx);
      } else {
        // Wrong order: TLS first, then PROXY header inside the encrypted tunnel.
        sendTlsThenProxy(ctx);
      }
    } else {
      // Non-TLS mode: send PROXY header + CONNECT directly.
      ctx.write(getHAProxyHeader());
      ctx.writeAndFlush(getConnectRequest());
    }
  }

  /** Correct order: cleartext PROXY header → TLS handshake → HTTP CONNECT. */
  private void sendProxyThenTls(ChannelHandlerContext ctx) {
    ByteBuf buf = ctx.alloc().buffer();
    buf.writeBytes(getHAProxyHeader().getBytes(StandardCharsets.US_ASCII));

    // Write through the pipeline head to bypass HttpRequestEncoder,
    // which only handles HttpRequest/HttpContent, not raw ByteBuf.
    ctx.pipeline()
        .firstContext()
        .writeAndFlush(buf)
        .addListener(
            (ChannelFutureListener)
                future -> {
                  if (!future.isSuccess()) {
                    signalTlsDone(false, future.cause());
                    ctx.close();
                    return;
                  }
                  addSslAndConnect(ctx);
                });
  }

  /**
   * Wrong order (for negative testing): TLS handshake first → PROXY header sent inside the
   * encrypted tunnel → proxy cannot decode it.
   */
  private void sendTlsThenProxy(ChannelHandlerContext ctx) {
    SslHandler sslHandler = clientSslContext.newHandler(ctx.alloc(), "localhost", proxyPort);
    ctx.pipeline().addFirst("ssl", sslHandler);

    sslHandler
        .handshakeFuture()
        .addListener(
            (GenericFutureListener<? extends Future<? super Channel>>)
                hsFuture -> {
                  signalTlsDone(hsFuture.isSuccess(), hsFuture.cause());
                  if (hsFuture.isSuccess()) {
                    // PROXY header is now encrypted — proxy can't decode it.
                    ByteBuf buf = ctx.alloc().buffer();
                    buf.writeBytes(getHAProxyHeader().getBytes(StandardCharsets.US_ASCII));
                    ctx.writeAndFlush(buf);
                    ctx.writeAndFlush(getConnectRequest());
                  } else {
                    ctx.close();
                  }
                });
  }

  /** Adds the SslHandler and sends CONNECT after the handshake succeeds. */
  private void addSslAndConnect(ChannelHandlerContext ctx) {
    ChannelPipeline pipeline = ctx.pipeline();
    SslHandler sslHandler = clientSslContext.newHandler(ctx.alloc(), "localhost", proxyPort);
    pipeline.addFirst("ssl", sslHandler);

    sslHandler
        .handshakeFuture()
        .addListener(
            (GenericFutureListener<? extends Future<? super Channel>>)
                hsFuture -> {
                  signalTlsDone(hsFuture.isSuccess(), hsFuture.cause());
                  if (hsFuture.isSuccess()) {
                    ctx.writeAndFlush(getConnectRequest());
                  } else {
                    ctx.close();
                  }
                });
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof HttpResponse) {
      ctx.close();
    }
  }

  private void signalTlsDone(boolean success, Throwable cause) {
    tlsHandshakeSuccess = success;
    tlsHandshakeFailureCause = cause;
    if (tlsHandshakeDone != null) {
      tlsHandshakeDone.countDown();
    }
  }

  boolean isTlsHandshakeSuccess() {
    return tlsHandshakeSuccess;
  }

  Throwable getTlsHandshakeFailureCause() {
    return tlsHandshakeFailureCause;
  }

  private HttpRequest getConnectRequest() {
    return new DefaultHttpRequest(
        HttpVersion.HTTP_1_1, HttpMethod.CONNECT, HOST + ":" + serverPort);
  }

  private String getHAProxyHeader() {
    return String.format(
        "PROXY TCP4 %s %s %s %s\r\n",
        proxyProtocolHeader.getSourceAddress(),
        proxyProtocolHeader.getDestinationAddress(),
        proxyProtocolHeader.getSourcePort(),
        proxyProtocolHeader.getDestinationPort());
  }
}
