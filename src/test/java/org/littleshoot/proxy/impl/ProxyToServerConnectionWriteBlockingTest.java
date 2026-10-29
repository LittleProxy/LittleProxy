package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;

/**
 * Regression test for <a href="https://github.com/LittleProxy/LittleProxy/issues/827">issue
 * #827</a>.
 *
 * <p>{@code ProxyToServerConnection.write()} used to block the calling (Netty event loop) thread on
 * {@code connectLock.wait(30000)} while the upstream connection was still being established. This
 * test drives {@code write()} from a separate thread while the connection is {@code CONNECTING} and
 * asserts that the call returns promptly (the message must be deferred, not waited on).
 */
final class ProxyToServerConnectionWriteBlockingTest {

  private final DefaultHttpProxyServer proxyServer = mock();
  private final ClientToProxyConnection clientConnection = mock();
  private final HttpFilters filters = mock();
  private final GlobalTrafficShapingHandler trafficHandler = mock();
  private final FlowContext flowContext = mock();
  private final FullFlowContext fullFlowContext = mock();
  private final InetSocketAddress hostAddress = new InetSocketAddress("127.0.0.1", 8080);

  private ProxyToServerConnection createConnection() throws UnknownHostException {
    HostResolver hostResolver = mock();
    when(proxyServer.getServerResolver()).thenReturn(hostResolver);
    when(hostResolver.resolve(any(), anyInt())).thenReturn(hostAddress);
    when(proxyServer.getActivityTrackers()).thenReturn(List.of());
    when(clientConnection.flowContext()).thenReturn(flowContext);
    when(clientConnection.flowContextForServerConnection(any())).thenReturn(fullFlowContext);
    // A write always originates from a client channel; the connection needs one to build the
    // deferred-write future. `channel` is an inherited protected field, set directly (same
    // package).
    clientConnection.channel = new EmbeddedChannel();
    return ProxyToServerConnection.create(
        proxyServer, clientConnection, "localhost:8080", filters, null, trafficHandler);
  }

  @Test
  @DisplayName("write() while CONNECTING must not block the calling thread")
  void writeWhileConnectingDoesNotBlockCaller() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.CONNECTING);

    Thread writer =
        new Thread(
            () -> connection.write(new DefaultHttpContent(Unpooled.buffer(1))),
            "write-while-connecting");
    writer.start();

    // The write must return on its own, well within the old 30s connect lock timeout.
    writer.join(2000);
    boolean returnedPromptly = !writer.isAlive();

    // Clean up a still-blocked writer (pre-fix behaviour) so the test JVM does not leak the thread.
    if (writer.isAlive()) {
      writer.interrupt();
      writer.join(5000);
    }

    assertThat(returnedPromptly)
        .as("write() must not block the event-loop thread while the connection is still CONNECTING")
        .isTrue();
  }

  @Test
  @DisplayName("a message deferred during CONNECTING is written once the connection succeeds")
  void deferredWriteIsDeliveredAfterConnectSucceeds() throws Exception {
    ProxyToServerConnection connection = createConnection();
    EmbeddedChannel serverChannel = new EmbeddedChannel();
    ChannelHandlerContext ctx = mock();
    when(ctx.channel()).thenReturn(serverChannel);
    connection.handlerAdded(ctx);
    connection.become(ConnectionState.CONNECTING);

    DefaultHttpContent content = new DefaultHttpContent(Unpooled.buffer(4).writeInt(42));

    ChannelFuture deferred = connection.write(content);

    // The write returned immediately (message queued, not sent).
    assertThat(deferred.isDone()).isFalse();
    assertThat((Object) serverChannel.readOutbound()).isNull();

    // Simulate the connection flow completing successfully.
    connection.connectionSucceeded(false);
    serverChannel.runPendingTasks();

    assertThat(deferred.isSuccess()).as("deferred write future should succeed").isTrue();
    assertThat((Object) serverChannel.readOutbound())
        .as("queued message should have been written to the server channel")
        .isSameAs(content);

    content.release();
    serverChannel.finishAndReleaseAll();
  }
}
