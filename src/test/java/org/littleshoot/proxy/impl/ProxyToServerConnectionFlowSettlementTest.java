package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
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
