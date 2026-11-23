package org.littleshoot.proxy.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the defensive catch blocks of the activity monitors in {@link ProxyConnection}. A broken
 * {@link ActivityTracker} must never drop a message or fail the pipeline: the monitor logs the
 * failure and still forwards the message to the next handler.
 */
final class ProxyConnectionMonitorTest {

  private final DefaultHttpProxyServer proxyServer = mock();
  private final ChannelHandlerContext ctx = mock();
  private final ChannelPromise promise = mock();

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
      };

  @Test
  @DisplayName("a throwing bytes-read tracker still forwards the buffer")
  void throwingBytesReadTrackerStillForwardsBuffer() throws Exception {
    ProxyConnection<HttpRequest>.BytesReadMonitor monitor =
        connection.new BytesReadMonitor() {
          @Override
          protected void bytesRead(int numberOfBytes) {
            throw new IllegalStateException("tracker failed");
          }
        };
    ByteBuf buffer = Unpooled.buffer(4).writeInt(1);

    monitor.channelRead(ctx, buffer);

    verify(ctx).fireChannelRead(buffer);
    buffer.release();
  }

  @Test
  @DisplayName("a throwing request-read tracker still forwards the request")
  void throwingRequestReadTrackerStillForwardsRequest() throws Exception {
    ProxyConnection<HttpRequest>.RequestReadMonitor monitor =
        connection.new RequestReadMonitor() {
          @Override
          protected void requestRead(HttpRequest httpRequest) {
            throw new IllegalStateException("tracker failed");
          }
        };
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

    monitor.channelRead(ctx, request);

    verify(ctx).fireChannelRead(request);
  }

  @Test
  @DisplayName("a throwing response-read tracker still forwards the response")
  void throwingResponseReadTrackerStillForwardsResponse() throws Exception {
    ProxyConnection<HttpRequest>.ResponseReadMonitor monitor =
        connection.new ResponseReadMonitor() {
          @Override
          protected void responseRead(HttpResponse httpResponse) {
            throw new IllegalStateException("tracker failed");
          }
        };
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

    monitor.channelRead(ctx, response);

    verify(ctx).fireChannelRead(response);
  }

  @Test
  @DisplayName("a throwing bytes-written tracker still writes the buffer")
  void throwingBytesWrittenTrackerStillWritesBuffer() throws Exception {
    ProxyConnection<HttpRequest>.BytesWrittenMonitor monitor =
        connection.new BytesWrittenMonitor() {
          @Override
          protected void bytesWritten(int numberOfBytes) {
            throw new IllegalStateException("tracker failed");
          }
        };
    ByteBuf buffer = Unpooled.buffer(4).writeInt(1);

    monitor.write(ctx, buffer, promise);

    verify(ctx).write(buffer, promise);
    buffer.release();
  }

  @Test
  @DisplayName("a throwing response-written tracker still writes the response")
  void throwingResponseWrittenTrackerStillWritesResponse() throws Exception {
    ProxyConnection<HttpRequest>.ResponseWrittenMonitor monitor =
        connection.new ResponseWrittenMonitor() {
          @Override
          protected void responseWritten(HttpResponse httpResponse) {
            throw new IllegalStateException("tracker failed");
          }
        };
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

    monitor.write(ctx, response, promise);

    verify(ctx).write(response, promise);
  }
}
