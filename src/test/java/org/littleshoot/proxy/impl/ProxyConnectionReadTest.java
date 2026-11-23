package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the inbound dispatch in {@link ProxyConnection#read(Object)}: HTTP objects, raw buffers,
 * HAProxy messages, and the states that deliberately drop or ignore a message. These branches are
 * easy to get wrong when the state machine changes, and a wrong dispatch either loses a chunk or
 * throws on the event loop.
 */
final class ProxyConnectionReadTest {

  private final DefaultHttpProxyServer proxyServer = mock();
  private final List<Object> rawReads = new ArrayList<>();
  private final List<Object> haproxyReads = new ArrayList<>();
  private final List<HttpObject> httpReads = new ArrayList<>();
  private final List<HttpContent> chunkReads = new ArrayList<>();

  private final ProxyConnection<HttpRequest> connection =
      new ProxyConnection<>(ConnectionState.AWAITING_INITIAL, proxyServer, false) {
        @Override
        ConnectionState readHTTPInitial(HttpRequest httpRequest) {
          httpReads.add(httpRequest);
          return getCurrentState();
        }

        @Override
        protected void readHTTPChunk(HttpContent chunk) {
          chunkReads.add(chunk);
        }

        @Override
        protected void readRaw(ByteBuf buf) {
          rawReads.add(buf);
        }

        @Override
        protected void readHAProxyMessage(HAProxyMessage msg) {
          haproxyReads.add(msg);
        }
      };

  @Test
  @DisplayName("an HTTP request is dispatched to the HTTP handler")
  void httpRequestIsDispatched() {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

    connection.read(request);

    assertThat(httpReads).containsExactly(request);
    assertThat(connection.getCurrentState()).isEqualTo(ConnectionState.AWAITING_INITIAL);
    assertThat(connection.lastReadTime).isPositive();
  }

  @Test
  @DisplayName("a raw buffer is dispatched to the raw handler")
  void rawBufferIsDispatched() {
    ByteBuf buffer = Unpooled.buffer(4).writeInt(1);

    connection.read(buffer);

    assertThat(rawReads).containsExactly(buffer);
    buffer.release();
  }

  @Test
  @DisplayName("a HAProxy message is dispatched to the HAProxy handler")
  void haproxyMessageIsDispatched() {
    HAProxyMessage message =
        new HAProxyMessage(
            HAProxyProtocolVersion.V1,
            HAProxyCommand.PROXY,
            HAProxyProxiedProtocol.TCP4,
            "1.2.3.4",
            "5.6.7.8",
            1234,
            80);

    connection.read(message);

    assertThat(haproxyReads).containsExactly(message);
    message.release();
  }

  @Test
  @DisplayName("an unsupported message type is rejected instead of being silently ignored")
  void unsupportedMessageIsRejected() {
    assertThatThrownBy(() -> connection.read("not a Netty message"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Unsupported message type");
  }

  @Test
  @DisplayName("a content chunk in AWAITING_INITIAL is dropped as orphaned short-circuit content")
  void contentInAwaitingInitialIsDropped() {
    HttpContent content = new DefaultHttpContent(Unpooled.buffer(1));

    connection.read(content);

    assertThat(httpReads).isEmpty();
    assertThat(chunkReads).isEmpty();
    assertThat(connection.getCurrentState()).isEqualTo(ConnectionState.AWAITING_INITIAL);
    content.release();
  }

  @Test
  @DisplayName("a content chunk in AWAITING_CHUNK is dispatched to the chunk handler")
  void contentInAwaitingChunkIsDispatched() {
    connection.become(ConnectionState.AWAITING_CHUNK);
    HttpContent content = new DefaultHttpContent(Unpooled.buffer(1));

    connection.read(content);

    assertThat(chunkReads).containsExactly(content);
    assertThat(connection.getCurrentState()).isEqualTo(ConnectionState.AWAITING_CHUNK);
    content.release();
  }

  @Test
  @DisplayName("an HTTP request in AWAITING_PROXY_AUTHENTICATION is dispatched")
  void requestInAwaitingProxyAuthenticationIsDispatched() {
    connection.become(ConnectionState.AWAITING_PROXY_AUTHENTICATION);
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

    connection.read(request);

    assertThat(httpReads).containsExactly(request);
    assertThat(connection.getCurrentState())
        .isEqualTo(ConnectionState.AWAITING_PROXY_AUTHENTICATION);
  }

  @Test
  @DisplayName("a message in AWAITING_CONNECT_OK is ignored and the state is preserved")
  void messageInAwaitingConnectOkIsIgnored() {
    connection.become(ConnectionState.AWAITING_CONNECT_OK);
    HttpContent content = new DefaultHttpContent(Unpooled.buffer(1));

    connection.read(content);

    assertThat(connection.getCurrentState()).isEqualTo(ConnectionState.AWAITING_CONNECT_OK);
    assertThat(chunkReads).isEmpty();
    content.release();
  }

  @Test
  @DisplayName("messages are ignored while connecting, handshaking, or closing")
  void transitionalStatesIgnoreMessages() {
    for (ConnectionState state :
        List.of(
            ConnectionState.CONNECTING,
            ConnectionState.NEGOTIATING_CONNECT,
            ConnectionState.HANDSHAKING,
            ConnectionState.DISCONNECT_REQUESTED,
            ConnectionState.DISCONNECTED)) {
      connection.become(state);
      HttpContent content = new DefaultHttpContent(Unpooled.buffer(1));

      connection.read(content);

      assertThat(connection.getCurrentState())
          .as("state %s must be preserved", state)
          .isEqualTo(state);
      assertThat(chunkReads).as("no chunk may be delivered in %s", state).isEmpty();
      content.release();
    }
  }
}
