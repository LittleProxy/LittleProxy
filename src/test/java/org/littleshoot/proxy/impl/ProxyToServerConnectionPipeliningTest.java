package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Requests pipelined on one pooled server connection (#858 follow-up). */
class ProxyToServerConnectionPipeliningTest {

  private final DefaultHttpProxyServer proxyServer = mock();
  private final HostResolver hostResolver = mock();
  private final GlobalTrafficShapingHandler trafficHandler = mock();
  private final EmbeddedChannel channel = new EmbeddedChannel();
  private final RecordingPool pool = new RecordingPool();

  private final HttpRequest requestA = request("/a");
  private final HttpRequest requestB = request("/b");
  private final HttpRequest requestC = request("/c");
  private final ClientToProxyConnection clientA = client();
  private final ClientToProxyConnection clientB = client();
  private final ClientToProxyConnection clientC = client();
  private final HttpFilters filtersA = mock();
  private final HttpFilters filtersB = mock();
  private final HttpFilters filtersC = mock();

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  @Test
  void eachResponseGoesToTheRequestItAnswers() throws Exception {
    ProxyToServerConnection connection = pooledConnection();
    pool.registerPendingRequest(channel, clientA, requestA, filtersA);
    pool.registerPendingRequest(channel, clientB, requestB, filtersB);
    pool.registerPendingRequest(channel, clientC, requestC, filtersC);

    FullHttpResponse responseA = response();
    FullHttpResponse responseB = response();
    FullHttpResponse responseC = response();
    connection.readHTTPInitial(responseA);
    connection.readHTTPInitial(responseB);
    connection.readHTTPInitial(responseC);

    ArgumentCaptor<HttpResponse> remembered = ArgumentCaptor.forClass(HttpResponse.class);
    verify(clientA)
        .respond(eq(connection), eq(filtersA), eq(requestA), remembered.capture(), eq(responseA));
    verify(clientB)
        .respond(eq(connection), eq(filtersB), eq(requestB), remembered.capture(), eq(responseB));
    verify(clientC)
        .respond(eq(connection), eq(filtersC), eq(requestC), remembered.capture(), eq(responseC));

    InOrder completions = inOrder(filtersA, filtersB, filtersC);
    completions.verify(filtersA).serverToProxyResponseReceived();
    completions.verify(filtersB).serverToProxyResponseReceived();
    completions.verify(filtersC).serverToProxyResponseReceived();

    assertThat(connection.currentHttpRequest()).isNull();
    assertThat(pool.released).isSameAs(connection);
  }

  @Test
  void requestWrittenWhileTheResponseCompletesIsNotLost() throws Exception {
    ProxyToServerConnection connection = pooledConnection();
    pool.registerPendingRequest(channel, clientA, requestA, filtersA);
    // The client's next request reaches the connection on the client's event loop after
    // markResponseComplete found no pending request, but before it cleared the current one.
    pool.onFirstEmptyPeek = () -> connection.writeHttp(requestB);

    connection.readHTTPInitial(response());

    assertThat(connection.currentHttpRequest())
        .as("the next request must be current, so that its response body is read")
        .isSameAs(requestB);
    assertThat(pool.released)
        .as("a connection with a request in flight must not go back to the pool")
        .isNull();
  }

  @Test
  void currentRequestIsSetWhenWrittenOnAnIdleConnection() throws Exception {
    ProxyToServerConnection connection = pooledConnection();
    pool.registerPendingRequest(channel, clientA, requestA, filtersA);
    connection.readHTTPInitial(response());
    assertThat(connection.currentHttpRequest()).isNull();

    connection.writeHttp(requestB);

    assertThat(connection.currentHttpRequest()).isSameAs(requestB);
  }

  @Test
  void requestWrittenDuringAResponseDoesNotReplaceTheCurrentOne() throws Exception {
    ProxyToServerConnection connection = pooledConnection();
    connection.writeHttp(requestA);
    assertThat(connection.currentHttpRequest()).isSameAs(requestA);

    connection.writeHttp(requestB);

    assertThat(connection.currentHttpRequest()).isSameAs(requestA);
  }

  private ProxyToServerConnection pooledConnection() throws UnknownHostException {
    when(proxyServer.getActivityTrackers()).thenReturn(Collections.emptyList());
    when(proxyServer.getServerResolver()).thenReturn(hostResolver);
    when(hostResolver.resolve(any(), anyInt())).thenReturn(new InetSocketAddress("127.0.0.1", 80));
    ProxyToServerConnection connection =
        ProxyToServerConnection.createForPool(
            proxyServer, pool, clientA, "localhost:80", null, filtersA, requestA, trafficHandler);
    connection.channel = channel;
    assertThat(connection.currentHttpRequest()).isNull();
    return connection;
  }

  private static HttpRequest request(String uri) {
    return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
  }

  private static FullHttpResponse response() {
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response.headers().set("Content-Length", 0);
    return response;
  }

  private static ClientToProxyConnection client() {
    ClientToProxyConnection client = mock();
    when(client.flowContext()).thenReturn(mock());
    when(client.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mock());
    return client;
  }

  /** A real pending-request queue that records releases and can act between two peeks. */
  private static final class RecordingPool extends ConcurrentMapServerConnectionPool {
    @Nullable volatile ProxyToServerConnection released;
    @Nullable volatile Runnable onFirstEmptyPeek;
    private final AtomicBoolean emptyPeekSeen = new AtomicBoolean();

    @Override
    public @Nullable PendingRequest peekPendingRequest(Channel channel) {
      PendingRequest pending = super.peekPendingRequest(channel);
      Runnable action = onFirstEmptyPeek;
      if (pending == null && action != null && emptyPeekSeen.compareAndSet(false, true)) {
        action.run();
      }
      return pending;
    }

    @Override
    public void releaseConnection(ProxyToServerConnection connection) {
      released = connection;
      super.releaseConnection(connection);
    }
  }
}
