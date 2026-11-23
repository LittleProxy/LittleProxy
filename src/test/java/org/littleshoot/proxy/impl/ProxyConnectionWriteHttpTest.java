package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the composite promise returned when writing a {@link LastHttpContent}: a
 * rejected content write must not be reported as a successful write just because the empty flush
 * marker that follows it was accepted, and the result must not succeed before both writes have
 * completed.
 */
final class ProxyConnectionWriteHttpTest {

  private final DefaultHttpProxyServer proxyServer = mock();

  @Test
  @DisplayName("a rejected LastHttpContent is reported even though the flush marker succeeds")
  void lastChunkWriteFailureIsPropagated() {
    EmbeddedChannel channel = new EmbeddedChannel();
    channel
        .pipeline()
        .addFirst(
            new ChannelOutboundHandlerAdapter() {
              @Override
              public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof LastHttpContent) {
                  ReferenceCountUtil.release(msg);
                  promise.setFailure(new IllegalStateException("content rejected"));
                  return;
                }
                ctx.write(msg, promise);
              }
            });

    ProxyConnection<HttpRequest> connection = newConnection();
    channel.pipeline().addLast(connection);

    LastHttpContent lastContent = new DefaultLastHttpContent(Unpooled.buffer(4).writeInt(1));
    ChannelFuture result = connection.writeHttp(lastContent);
    channel.runPendingTasks();

    assertThat(result.isDone()).isTrue();
    assertThat(result.isSuccess())
        .as("the content write failed, so the returned future must fail too")
        .isFalse();
    assertThat(result.cause()).hasMessage("content rejected");
    assertThat((Object) channel.readOutbound())
        .as("the empty flush marker must still be written")
        .isNotNull();

    channel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("the result is not complete until the content write completes")
  void lastChunkWriteWaitsForContentWrite() {
    EmbeddedChannel channel = new EmbeddedChannel();
    DelayedContentWriteHandler delayedContentWrite = new DelayedContentWriteHandler();
    channel.pipeline().addFirst(delayedContentWrite);

    ProxyConnection<HttpRequest> connection = newConnection();
    channel.pipeline().addLast(connection);

    LastHttpContent lastContent = new DefaultLastHttpContent(Unpooled.buffer(4).writeInt(1));
    ChannelFuture result = connection.writeHttp(lastContent);
    channel.runPendingTasks();

    assertThat(result.isDone())
        .as("the content write has not completed, so the result must still be pending")
        .isFalse();
    assertThat((Object) channel.readOutbound())
        .as("the empty flush marker must still be written")
        .isNotNull();

    delayedContentWrite.complete();

    assertThat(result.isDone()).isTrue();
    assertThat(result.isSuccess())
        .as("both the content write and the flush marker succeeded")
        .isTrue();

    channel.finishAndReleaseAll();
  }

  private ProxyConnection<HttpRequest> newConnection() {
    return new ProxyConnection<>(ConnectionState.AWAITING_CHUNK, proxyServer, false) {
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

  /** Accepts the last content write but holds its promise until the test completes it. */
  private static final class DelayedContentWriteHandler extends ChannelOutboundHandlerAdapter {
    private Object heldContent;
    private ChannelPromise heldPromise;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
      if (msg instanceof LastHttpContent) {
        heldContent = msg;
        heldPromise = promise;
        return;
      }
      ctx.write(msg, promise);
    }

    void complete() {
      ReferenceCountUtil.release(heldContent);
      heldPromise.setSuccess();
    }
  }
}
