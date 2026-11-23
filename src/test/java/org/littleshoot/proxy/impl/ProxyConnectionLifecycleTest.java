package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.IOException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersSource;

/**
 * Covers the {@link ProxyConnection} lifecycle hooks that the connection flow drives but that are
 * hard to reach through an end-to-end proxy test: starting a tunnel, adding the second SSL handler
 * when chaining through an encrypted proxy, and looking up filters from the server.
 */
final class ProxyConnectionLifecycleTest {

  private final DefaultHttpProxyServer proxyServer = mock();

  @Test
  @DisplayName("StartTunneling removes the HTTP handlers and reports the tunnel")
  void startTunnelingRemovesHttpHandlers() {
    ProxyConnection<HttpRequest> connection = newConnection();
    EmbeddedChannel channel = new EmbeddedChannel();
    channel.pipeline().addLast("encoder", new NoopHandler());
    channel.pipeline().addLast("decoder", new NoopHandler());
    channel.pipeline().addLast("responseWrittenMonitor", new NoopHandler());
    channel.pipeline().addLast("requestReadMonitor", new NoopHandler());
    connection.channel = channel;
    connection.ctx = channel.pipeline().context("encoder");

    Future<?> result = connection.StartTunneling.execute();

    assertThat(result.isSuccess()).isTrue();
    assertThat(connection.isTunneling()).isTrue();
    assertThat(channel.pipeline().get("encoder")).isNull();
    assertThat(channel.pipeline().get("decoder")).isNull();
    assertThat(channel.pipeline().get("responseWrittenMonitor")).isNull();
    assertThat(channel.pipeline().get("requestReadMonitor")).isNull();
    channel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("StartTunneling returns a failed future instead of throwing when the pipeline fails")
  void startTunnelingReportsPipelineFailure() {
    ProxyConnection<HttpRequest> connection = newConnection();
    EmbeddedChannel channel = new EmbeddedChannel();
    connection.channel = channel;
    ChannelHandlerContext ctx = mock();
    when(ctx.pipeline()).thenThrow(new IllegalStateException("pipeline is gone"));
    connection.ctx = ctx;

    Future<?> result = connection.StartTunneling.execute();

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.cause()).hasMessage("pipeline is gone");
    channel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("encrypt adds a second SSL handler when the pipeline already has one")
  void encryptAddsSecondSslHandlerForChainedProxy() throws Exception {
    ProxyConnection<HttpRequest> connection = newConnection();
    ChannelPipeline pipeline = mock(ChannelPipeline.class);
    when(pipeline.get("ssl")).thenReturn(mock(ChannelHandler.class));
    SSLEngine engine = SSLContext.getDefault().createSSLEngine();

    connection.encrypt(pipeline, engine, false);

    verify(pipeline).addAfter(eq("ssl"), eq("sslWithServer"), any());
    assertThat(connection.getSslEngine()).isSameAs(engine);
  }

  @Test
  @DisplayName("encrypt adds the first SSL handler when the pipeline has none")
  void encryptAddsFirstSslHandler() throws Exception {
    ProxyConnection<HttpRequest> connection = newConnection();
    ChannelPipeline pipeline = mock(ChannelPipeline.class);
    when(pipeline.get("ssl")).thenReturn(null);
    SSLEngine engine = SSLContext.getDefault().createSSLEngine();

    connection.encrypt(pipeline, engine, false);

    verify(pipeline).addFirst(eq("ssl"), any());
  }

  @Test
  @DisplayName("EncryptChannel requires client authentication for a server-side connection")
  void encryptChannelAuthenticatesClientsWhenNotSslClient() throws Exception {
    ProxyConnection<HttpRequest> connection = newConnection();
    ChannelPipeline pipeline = mock(ChannelPipeline.class);
    when(pipeline.get("ssl")).thenReturn(null);
    ChannelHandlerContext ctx = mock();
    when(ctx.pipeline()).thenReturn(pipeline);
    connection.ctx = ctx;
    SSLEngine engine = SSLContext.getDefault().createSSLEngine();

    connection.EncryptChannel(engine).execute();

    assertThat(engine.getNeedClientAuth()).isTrue();
  }

  @Test
  @DisplayName("filters are resolved from the proxy server's filter source")
  void filtersAreResolvedFromProxyServer() {
    HttpFiltersSource filtersSource = mock();
    HttpFilters expected = mock();
    when(proxyServer.getFiltersSource()).thenReturn(filtersSource);
    HttpRequest request = mock();
    ChannelHandlerContext ctx = mock();
    when(filtersSource.filterRequest(request, ctx)).thenReturn(expected);
    ProxyConnection<HttpRequest> connection = newConnection();
    connection.ctx = ctx;

    assertThat(connection.getHttpFiltersFromProxyServer(request)).isSameAs(expected);
  }

  @Test
  @DisplayName("the base exceptionCaught swallows the exception")
  void baseExceptionCaughtIsNoOp() {
    ProxyConnection<HttpRequest> connection = newConnection();

    assertThatCode(
            () ->
                connection.exceptionCaught(
                    mock(ChannelHandlerContext.class), new RuntimeException("ignored")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("disconnect reports a channel close failure")
  @SuppressWarnings("unchecked")
  void disconnectReportsCloseFailure() {
    ProxyConnection<HttpRequest> connection = newConnection();
    Channel channel = mock(Channel.class);
    ChannelFuture writeFuture = mock(ChannelFuture.class);
    when(writeFuture.isSuccess()).thenReturn(true);
    when(writeFuture.addListener(any()))
        .thenAnswer(
            invocation -> {
              GenericFutureListener<Future<? super Void>> listener = invocation.getArgument(0);
              listener.operationComplete(writeFuture);
              return writeFuture;
            });
    when(channel.writeAndFlush(any())).thenReturn(writeFuture);
    ChannelFuture closeFuture = mock(ChannelFuture.class);
    when(closeFuture.isSuccess()).thenReturn(false);
    IOException cause = new IOException("close failed");
    when(closeFuture.cause()).thenReturn(cause);
    when(closeFuture.addListener(any()))
        .thenAnswer(
            invocation -> {
              GenericFutureListener<Future<? super Void>> listener = invocation.getArgument(0);
              listener.operationComplete(closeFuture);
              return closeFuture;
            });
    when(channel.close()).thenReturn(closeFuture);
    ChannelPromise promise = mock(ChannelPromise.class);
    when(channel.newPromise()).thenReturn(promise);
    connection.channel = channel;

    Future<Void> result = connection.disconnect();

    assertThat(result).isSameAs(promise);
    verify(promise).setFailure(cause);
  }

  private ProxyConnection<HttpRequest> newConnection() {
    return new ProxyConnection<>(ConnectionState.AWAITING_INITIAL, proxyServer, false) {
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
    };
  }

  /** Minimal handler used to populate a pipeline by name. */
  private static final class NoopHandler extends ChannelHandlerAdapter {}
}
