package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;

/**
 * Regression test for review item R2: a filter that rejects the upstream response during connection
 * setup must settle the flow instead of leaving the queued writes and their read-gate holds behind.
 */
final class ProxyToServerConnectionFlowSettlementTest {

  private final DefaultHttpProxyServer proxyServer = mock();
  private final ClientToProxyConnection clientConnection = mock();
  private final HttpFilters filters = mock();
  private final GlobalTrafficShapingHandler trafficHandler = mock();
  private final FlowContext flowContext = mock();
  private final FullFlowContext fullFlowContext = mock();
  private final InetSocketAddress hostAddress = new InetSocketAddress("127.0.0.1", 8080);

  @Test
  @DisplayName("a filter that rejects the CONNECT response settles the flow and the queued writes")
  void filterRejectedConnectResponseSettlesFlow() throws Exception {
    ProxyToServerConnection connection = createConnection();
    EmbeddedChannel serverChannel = new EmbeddedChannel();
    ChannelHandlerContext ctx = mock();
    when(ctx.channel()).thenReturn(serverChannel);
    connection.handlerAdded(ctx);
    connection.become(ConnectionState.AWAITING_CONNECT_OK);
    connection.setConnectionFlow(new ConnectionFlow(clientConnection, connection, new Object()));

    // What the unrecoverable branch of serverConnectionFailed() does for this attempt: fail the
    // writes queued while connecting and do not retry.
    AtomicReference<Throwable> reportedCause = new AtomicReference<>();
    when(clientConnection.serverConnectionFailed(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              reportedCause.set(invocation.getArgument(2));
              connection.failPendingWrites(reportedCause.get());
              return false;
            });

    // A client borrowed this connection mid-CONNECT; its body chunk is queued on it.
    DefaultHttpContent content = new DefaultHttpContent(Unpooled.buffer(4).writeInt(1));
    ChannelFuture deferred = connection.write(content);
    assertThat(deferred.isDone()).isFalse();

    HttpResponse connectResponse =
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    when(filters.serverToProxyResponse(connectResponse)).thenReturn(null);
    connection.read(connectResponse);
    serverChannel.runPendingTasks();

    verify(clientConnection)
        .serverConnectionFailed(
            connection, ConnectionState.DISCONNECT_REQUESTED, reportedCause.get());
    assertThat(reportedCause.get()).hasMessageContaining("Filter rejected");

    assertThat(deferred.isDone())
        .as("the queued write's promise must be completed, not leaked")
        .isTrue();
    assertThat(deferred.isSuccess()).isFalse();
    assertThat(content.refCnt()).as("the retained reference must be released").isEqualTo(1);
    verify(clientConnection, times(1)).deferredWriteFinished();

    content.release();
    serverChannel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("a second failure notification for a connecting attempt does not report it again")
  void secondFailureNotificationForConnectingAttemptIsIgnored() throws Exception {
    ProxyToServerConnection connection = createConnection();
    EmbeddedChannel serverChannel = new EmbeddedChannel();
    ChannelHandlerContext ctx = mock();
    when(ctx.channel()).thenReturn(serverChannel);
    connection.handlerAdded(ctx);
    connection.become(ConnectionState.HANDSHAKING);
    ConnectionFlow flow = new ConnectionFlow(clientConnection, connection, new Object());
    connection.setConnectionFlow(flow);

    // A reset on the socket read is reported first, and starts the fallback to the direct
    // connection.
    IOException reset = new IOException("Connection reset");
    connection.exceptionCaught(reset);

    // The in-flight step then reports the same close (the SslHandler's handshake future fails when
    // the channel is closed). It belongs to the attempt that was already reported: reporting it
    // again would tell the client twice and abort the retry that is already connecting.
    flow.fail(new ClosedChannelException());
    serverChannel.runPendingTasks();

    assertThat(reportsToClient())
        .as("one connection attempt must report its failure exactly once")
        .isEqualTo(1);
    verify(clientConnection).serverConnectionFailed(connection, ConnectionState.HANDSHAKING, reset);

    serverChannel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("a synchronous retry does not make the finally block touch the replacement attempt")
  void failureStaysBoundToTheAttemptThatWasCurrentWhenItArrived() throws Exception {
    ProxyToServerConnection connection = createConnection();
    connection.become(ConnectionState.HANDSHAKING);
    ConnectionFlow original = mock();
    ConnectionFlow replacement = mock();
    connection.setConnectionFlow(original);

    ProxyConnectException cause = new ProxyConnectException("proxy refused");
    // The original attempt's failure can synchronously install a retry's flow
    // (resetConnectionForRetry + connectAndWrite assign the field) before exceptionCaught reaches
    // its finally block.
    doAnswer(
            invocation -> {
              connection.setConnectionFlow(replacement);
              return null;
            })
        .when(original)
        .fail(cause);

    connection.exceptionCaught(cause);

    verifyNoInteractions(replacement);
  }

  @Test
  @DisplayName("a connecting failure is reported to the client that started the flow")
  void connectingFailureIsReportedToTheFlowOwner() throws Exception {
    // The connection was created for clientConnection, but another client owns this attempt's
    // flow, as happens when a pool lends a still-connecting connection to a second client.
    ProxyToServerConnection connection = createConnection();
    ClientToProxyConnection flowOwner = mock();
    EmbeddedChannel serverChannel = new EmbeddedChannel();
    ChannelHandlerContext ctx = mock();
    when(ctx.channel()).thenReturn(serverChannel);
    connection.handlerAdded(ctx);
    connection.become(ConnectionState.HANDSHAKING);
    connection.setConnectionFlow(new ConnectionFlow(flowOwner, connection, new Object()));

    IOException cause = new IOException("upstream reset");
    connection.exceptionCaught(cause);

    verify(flowOwner).serverConnectionFailed(connection, ConnectionState.HANDSHAKING, cause);
    verify(clientConnection, never())
        .serverConnectionFailed(connection, ConnectionState.HANDSHAKING, cause);

    serverChannel.finishAndReleaseAll();
  }

  /**
   * Every {@code serverConnectionFailed} notification on the client, whatever state or cause it
   * carries, so the assertion does not depend on which notification arrived first.
   */
  private long reportsToClient() {
    return mockingDetails(clientConnection).getInvocations().stream()
        .filter(invocation -> "serverConnectionFailed".equals(invocation.getMethod().getName()))
        .count();
  }

  private ProxyToServerConnection createConnection() throws UnknownHostException {
    HostResolver hostResolver = mock();
    when(proxyServer.getServerResolver()).thenReturn(hostResolver);
    when(hostResolver.resolve(any(), anyInt())).thenReturn(hostAddress);
    when(proxyServer.getActivityTrackers()).thenReturn(List.of());
    when(clientConnection.flowContext()).thenReturn(flowContext);
    when(clientConnection.flowContextForServerConnection(any())).thenReturn(fullFlowContext);
    clientConnection.channel = new EmbeddedChannel();
    when(clientConnection.getCurrentState()).thenReturn(ConnectionState.AWAITING_INITIAL);
    return ProxyToServerConnection.create(
        proxyServer, clientConnection, "localhost:8080", filters, null, trafficHandler);
  }
}
