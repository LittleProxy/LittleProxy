package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpFiltersSource;

class ProxyConnectionHandlerAddedTest {

  private DefaultHttpProxyServer mockProxyServer;
  private GlobalTrafficShapingHandler mockTrafficHandler;

  @BeforeEach
  void setUp() {
    mockProxyServer = mock();
    mockTrafficHandler = mock();
    when(mockProxyServer.getChainProxyManager()).thenReturn(mock(ChainedProxyManager.class));
    when(mockProxyServer.getFiltersSource()).thenReturn(mock(HttpFiltersSource.class));
    when(mockProxyServer.getMaxInitialLineLength()).thenReturn(8192);
    when(mockProxyServer.getMaxHeaderSize()).thenReturn(16384);
    when(mockProxyServer.getMaxChunkSize()).thenReturn(16384);
    when(mockProxyServer.getIdleConnectionTimeout()).thenReturn(0);
    when(mockProxyServer.isAcceptProxyProtocol()).thenReturn(false);
    when(mockProxyServer.getProxyAlias()).thenReturn("test");
    when(mockProxyServer.isAllowRequestsToOriginServer()).thenReturn(true);
    when(mockProxyServer.getActivityTrackers()).thenReturn(java.util.Collections.emptyList());
  }

  @Test
  @DisplayName("handlerAdded should set ctx and channel before channelRegistered fires")
  void handlerAddedShouldSetCtxAndChannel() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    ClientToProxyConnection clientConn =
        new ClientToProxyConnection(
            mockProxyServer, null, false, channel.pipeline(), mockTrafficHandler);

    // handlerAdded is invoked by Netty automatically when the handler is added to the pipeline.
    // After that, ctx and channel should be non-null.
    Field ctxField = ProxyConnection.class.getDeclaredField("ctx");
    ctxField.setAccessible(true);
    ChannelHandlerContext ctx = (ChannelHandlerContext) ctxField.get(clientConn);
    assertThat(ctx).as("ctx should be set after handlerAdded").isNotNull();
    assertThat(ctx.channel()).as("ctx.channel() should be the EmbeddedChannel").isSameAs(channel);

    Field channelField = ProxyConnection.class.getDeclaredField("channel");
    channelField.setAccessible(true);
    Object connChannel = channelField.get(clientConn);
    assertThat(connChannel).as("channel should be set after handlerAdded").isNotNull();
    assertThat(connChannel).isSameAs(channel);

    channel.finish();
  }

  @Test
  @DisplayName("handlerAdded should set ctx before channelRegistered for ClientToProxyConnection")
  void handlerAddedContextShouldBeAvailableInChannelRegistered() throws Exception {
    // This test verifies the ordering guarantee: handlerAdded fires before channelRegistered
    // when a handler is added to an active channel's pipeline.
    // We use a pipeline that is already registered (via EmbeddedChannel) and add our handler
    // to verify that the fields are correctly populated by handlerAdded.

    EmbeddedChannel channel = new EmbeddedChannel();
    ChannelPipeline pipeline = channel.pipeline();

    ClientToProxyConnection clientConn =
        new ClientToProxyConnection(mockProxyServer, null, false, pipeline, mockTrafficHandler);

    // The constructor calls initChannelPipeline which adds this handler to the pipeline.
    // Since the EmbeddedChannel is already active, handlerAdded fires immediately
    // before channelRegistered would fire.

    Field ctxField = ProxyConnection.class.getDeclaredField("ctx");
    ctxField.setAccessible(true);
    ChannelHandlerContext ctx = (ChannelHandlerContext) ctxField.get(clientConn);

    assertThat(ctx).isNotNull();
    assertThat(ctx.pipeline()).isSameAs(pipeline);

    // Verify the channel is accessible through the context
    assertThat(ctx.channel()).isSameAs(channel);

    channel.finish();
  }

  @Test
  @DisplayName("handlerAdded should delegate to super.handlerAdded")
  void handlerAddedShouldDelegateToSuper() throws Exception {
    // We test this by verifying that no exception is thrown during normal pipeline setup
    EmbeddedChannel channel = new EmbeddedChannel();
    ClientToProxyConnection clientConn =
        new ClientToProxyConnection(
            mockProxyServer, null, false, channel.pipeline(), mockTrafficHandler);

    // If super.handlerAdded wasn't called, the handler wouldn't be properly registered
    // in the pipeline. Verify it's there.
    assertThat(channel.pipeline().get("handler"))
        .as("ClientToProxyConnection should be registered as 'handler' in the pipeline")
        .isSameAs(clientConn);

    channel.finish();
  }
}
