package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.ReferenceCounted;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    when(clientConnection.getCurrentState()).thenReturn(ConnectionState.AWAITING_INITIAL);
    return ProxyToServerConnection.create(
        proxyServer, clientConnection, "localhost:8080", filters, null, trafficHandler);
  }

  @Test
  @DisplayName("upstream close during CONNECT negotiation fails queued writes")
  void upstreamCloseDuringConnectNegotiationFailsQueuedWrites() throws Exception {
    ProxyToServerConnection connection = createConnection();
    // Mid-CONNECT: isConnecting() is true (so writes are queued) and isConnected() is true (so the
    // pool can hand this connection to another client).
    connection.become(ConnectionState.NEGOTIATING_CONNECT);

    DefaultHttpContent content = new DefaultHttpContent(Unpooled.buffer(4).writeInt(1));
    ChannelFuture deferred = connection.write(content);
    assertThat(deferred.isDone()).isFalse();

    // The upstream closes while the CONNECT negotiation is still in flight.
    connection.disconnected();

    assertThat(deferred.isDone())
        .as("the queued write's promise must be completed when the upstream closes")
        .isTrue();
    assertThat(deferred.isSuccess()).isFalse();
    assertThat(content.refCnt()).as("the retained reference must be released").isEqualTo(1);
    content.release();
  }

  @Test
  @DisplayName("a retry close does not drain queued writes")
  void retryCloseKeepsQueuedWrites() throws Exception {
    ProxyToServerConnection connection = createConnection();
    EmbeddedChannel serverChannel = new EmbeddedChannel();
    serverChannel.pipeline().addLast(connection);
    connection.become(ConnectionState.NEGOTIATING_CONNECT);

    DefaultHttpContent content = new DefaultHttpContent(Unpooled.buffer(4).writeInt(1));
    ChannelFuture deferred = connection.write(content);
    assertThat(deferred.isDone()).isFalse();

    // resetConnectionForRetry(): remove the handler, then close the abandoned channel. The removed
    // handler must not receive channelInactive, so the queued write survives for the retry.
    serverChannel.pipeline().remove(connection);
    serverChannel.close();

    assertThat(deferred.isDone())
        .as("a retry must keep the queued write so the retried request is not truncated")
        .isFalse();

    connection.failPendingWrites(null);
    content.release();
    serverChannel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("failPendingWritesFor only drains the given client's writes")
  void failPendingWritesForOnlyDrainsThatClient() throws Exception {
    ProxyToServerConnection connection = createPooledConnection();
    connection.become(ConnectionState.NEGOTIATING_CONNECT);

    ClientToProxyConnection clientA = mock();
    clientA.channel = new EmbeddedChannel();
    when(clientA.getCurrentState()).thenReturn(ConnectionState.AWAITING_INITIAL);
    ClientToProxyConnection clientB = mock();
    clientB.channel = new EmbeddedChannel();
    when(clientB.getCurrentState()).thenReturn(ConnectionState.AWAITING_INITIAL);

    connection.setCurrentClientConnectionForRequest(clientA);
    DefaultHttpContent contentA = new DefaultHttpContent(Unpooled.buffer(1).writeByte(1));
    ChannelFuture futureA = connection.write(contentA);

    connection.setCurrentClientConnectionForRequest(clientB);
    DefaultHttpContent contentB = new DefaultHttpContent(Unpooled.buffer(1).writeByte(2));
    ChannelFuture futureB = connection.write(contentB);

    connection.failPendingWritesFor(clientA);

    assertThat(futureA.isDone()).isTrue();
    assertThat(futureA.isSuccess()).isFalse();
    assertThat(contentA.refCnt()).isEqualTo(1);
    assertThat(futureB.isDone())
        .as("another client's queued write must be left untouched")
        .isFalse();

    connection.failPendingWrites(null);
    contentA.release();
    contentB.release();
  }

  private ProxyToServerConnection createPooledConnection() throws UnknownHostException {
    HostResolver hostResolver = mock();
    when(proxyServer.getServerResolver()).thenReturn(hostResolver);
    when(hostResolver.resolve(any(), anyInt())).thenReturn(hostAddress);
    when(proxyServer.getActivityTrackers()).thenReturn(List.of());
    when(clientConnection.flowContext()).thenReturn(flowContext);
    when(clientConnection.flowContextForServerConnection(any())).thenReturn(fullFlowContext);
    return ProxyToServerConnection.createForPool(
        proxyServer,
        mock(ServerConnectionPool.class),
        clientConnection,
        "localhost:8080",
        null,
        filters,
        mock(HttpRequest.class),
        trafficHandler);
  }

  @Test
  @DisplayName("write() while CONNECTING must not block the calling thread")
  void writeWhileConnectingDoesNotBlockCaller() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.CONNECTING);

    DefaultHttpContent content = new DefaultHttpContent(Unpooled.buffer(1));
    Thread writer = new Thread(() -> connection.write(content), "write-while-connecting");
    writer.start();

    // The write must return on its own, well within the old 30s connect lock timeout.
    writer.join(2000);
    boolean returnedPromptly = !writer.isAlive();

    // Clean up a still-blocked writer (pre-fix behaviour) so the test JVM does not leak the thread.
    if (writer.isAlive()) {
      writer.interrupt();
      writer.join(5000);
    }

    // Drain and release the queued message so the test does not leak its retained buffer.
    connection.failPendingWrites(null);
    content.release();

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
    Object outbound = serverChannel.readOutbound();
    assertThat(outbound)
        .as("queued message should have been written to the server channel")
        .isSameAs(content);

    // write() retained an extra reference for the queued message; readOutbound() transferred it to
    // this test, so release both references.
    ((ReferenceCounted) outbound).release();
    content.release();
    serverChannel.finishAndReleaseAll();
  }

  @Test
  @DisplayName(
      "a deferred message is dropped if the client disconnects before the connection succeeds")
  void deferredWriteIsDroppedWhenClientDisconnects() throws Exception {
    ProxyToServerConnection connection = createConnection();
    EmbeddedChannel serverChannel = new EmbeddedChannel();
    ChannelHandlerContext ctx = mock();
    when(ctx.channel()).thenReturn(serverChannel);
    connection.handlerAdded(ctx);
    connection.become(ConnectionState.CONNECTING);

    DefaultHttpContent content = new DefaultHttpContent(Unpooled.buffer(4).writeInt(7));
    ChannelFuture deferred = connection.write(content);

    // The client goes away while the connection is still being established (e.g. a shared-pool
    // connection that is not tracked in serverConnectionsByHostAndPort).
    when(clientConnection.getCurrentState()).thenReturn(ConnectionState.DISCONNECTED);

    connection.connectionSucceeded(false);
    serverChannel.runPendingTasks();

    assertThat(deferred.isDone()).isTrue();
    assertThat(deferred.isSuccess()).isFalse();
    assertThat((Object) serverChannel.readOutbound())
        .as("data must not be sent upstream for a disconnected client")
        .isNull();
    assertThat(content.refCnt()).as("the retained reference was released").isEqualTo(1);
    content.release();
    serverChannel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("failPendingWrites fails the promise and releases the retained buffer")
  void failPendingWritesFailsPromiseAndReleasesBuffer() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.CONNECTING);

    DefaultHttpContent content = new DefaultHttpContent(Unpooled.buffer(4).writeInt(1));
    ChannelFuture deferred = connection.write(content);
    assertThat(deferred.isDone()).isFalse();

    connection.failPendingWrites(new Exception("upstream failed"));

    assertThat(deferred.isDone()).isTrue();
    assertThat(deferred.isSuccess()).isFalse();
    assertThat(content.refCnt()).as("back to the single reference held by the caller").isEqualTo(1);
    content.release();
  }

  @Test
  @DisplayName("draining on client disconnect does not report a connection failure to filters")
  void clientDisconnectDrainDoesNotReportConnectionFailed() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.CONNECTING);

    connection.failPendingWrites(new Exception("client disconnected"));

    verify(filters, never()).proxyToServerConnectionFailed();
    assertThat(connection.getCurrentState())
        .as("the client-disconnect drain must not change the connection state")
        .isEqualTo(ConnectionState.CONNECTING);
  }

  @Test
  @DisplayName("queued chunks are written after the initial request, in order")
  void queuedChunksFollowInitialRequestInOrder() throws Exception {
    ProxyToServerConnection connection = createConnection();
    EmbeddedChannel serverChannel = new EmbeddedChannel();
    ChannelHandlerContext ctx = mock();
    when(ctx.channel()).thenReturn(serverChannel);
    connection.handlerAdded(ctx);
    connection.become(ConnectionState.CONNECTING);

    HttpRequest initialRequest =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
    connection.setInitialRequest(initialRequest);
    DefaultHttpContent chunk1 = new DefaultHttpContent(Unpooled.buffer(1).writeByte(1));
    DefaultHttpContent chunk2 = new DefaultHttpContent(Unpooled.buffer(1).writeByte(2));
    connection.write(chunk1);
    connection.write(chunk2);

    connection.connectionSucceeded(true);
    serverChannel.runPendingTasks();

    assertThat((Object) serverChannel.readOutbound()).isSameAs(initialRequest);
    assertThat((Object) serverChannel.readOutbound()).isSameAs(chunk1);
    assertThat((Object) serverChannel.readOutbound()).isSameAs(chunk2);
    assertThat((Object) serverChannel.readOutbound()).isNull();

    // Each chunk carries one reference from the test and one retained by write(); the initial
    // request is not reference counted.
    chunk1.release();
    chunk1.release();
    chunk2.release();
    chunk2.release();
    serverChannel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("a write racing connectionSucceeded is ordered after the initial request and chunks")
  void concurrentWriteDuringConnectionSucceededIsOrderedCorrectly() throws Exception {
    ProxyToServerConnection connection = createConnection();
    EmbeddedChannel serverChannel = new EmbeddedChannel();
    ChannelHandlerContext ctx = mock();
    when(ctx.channel()).thenReturn(serverChannel);
    connection.handlerAdded(ctx);
    connection.become(ConnectionState.CONNECTING);

    HttpRequest initialRequest =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
    connection.setInitialRequest(initialRequest);
    DefaultHttpContent queuedChunk = new DefaultHttpContent(Unpooled.buffer(1).writeByte(1));
    connection.write(queuedChunk);

    // connectionSucceeded() resumes reading before it writes the initial request and flushes the
    // queued chunk. Simulate a chunk read at exactly that point, on another thread.
    CountDownLatch insideSucceeded = new CountDownLatch(1);
    CountDownLatch writerReady = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              insideSucceeded.countDown();
              writerReady.await(5, TimeUnit.SECONDS);
              // Give a write that (wrongly) skipped connectLock time to run ahead.
              Thread.sleep(200);
              return null;
            })
        .when(clientConnection)
        .serverConnectionSucceeded(any(), anyBoolean());

    Thread succeededThread = new Thread(() -> connection.connectionSucceeded(true), "succeeded");
    succeededThread.start();
    assertThat(insideSucceeded.await(5, TimeUnit.SECONDS)).isTrue();

    DefaultHttpContent racingChunk = new DefaultHttpContent(Unpooled.buffer(1).writeByte(2));
    Thread writer =
        new Thread(
            () -> {
              writerReady.countDown();
              connection.write(racingChunk);
            },
            "racing-writer");
    writer.start();

    succeededThread.join(5000);
    writer.join(5000);
    serverChannel.runPendingTasks();

    assertThat((Object) serverChannel.readOutbound()).isSameAs(initialRequest);
    assertThat((Object) serverChannel.readOutbound()).isSameAs(queuedChunk);
    assertThat((Object) serverChannel.readOutbound()).isSameAs(racingChunk);
    assertThat((Object) serverChannel.readOutbound()).isNull();

    queuedChunk.release();
    queuedChunk.release();
    racingChunk.release();
    racingChunk.release();
    serverChannel.finishAndReleaseAll();
  }
}
