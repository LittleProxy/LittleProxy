package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpFiltersAdapterTest {

  private HttpRequest originalRequest;
  private ChannelHandlerContext ctx;
  private HttpObject httpObject;
  private HttpResponse httpResponse;

  @BeforeEach
  void setUp() {
    originalRequest = mock(HttpRequest.class);
    ctx = mock(ChannelHandlerContext.class);
    httpObject = mock(HttpObject.class);
    httpResponse = mock(HttpResponse.class);
  }

  @Test
  void testConstructorWithRequestAndContext() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThat(adapter.originalRequest).isEqualTo(originalRequest);
    assertThat(adapter.ctx).isEqualTo(ctx);
  }

  @Test
  void testConstructorWithRequestOnly() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest);

    assertThat(adapter.originalRequest).isEqualTo(originalRequest);
    assertThat(adapter.ctx).isNull();
  }

  @Test
  void testNoopFilter() {
    assertThat(HttpFiltersAdapter.NOOP_FILTER).isNotNull();
    assertThat(HttpFiltersAdapter.NOOP_FILTER.originalRequest).isNull();
  }

  @Test
  void testClientToProxyRequest() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    HttpResponse result = adapter.clientToProxyRequest(httpObject);

    assertThat(result).isNull();
  }

  @Test
  void testProxyToServerRequest() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    HttpResponse result = adapter.proxyToServerRequest(httpObject);

    assertThat(result).isNull();
  }

  @Test
  void testProxyToServerRequestSending() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(adapter::proxyToServerRequestSending).doesNotThrowAnyException();
  }

  @Test
  void testProxyToServerRequestSent() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(adapter::proxyToServerRequestSent).doesNotThrowAnyException();
  }

  @Test
  void testServerToProxyResponse() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);
    HttpObject input = mock(HttpObject.class);

    HttpObject result = adapter.serverToProxyResponse(input);

    assertThat(result).isSameAs(input);
  }

  @Test
  void testServerToProxyResponseTimedOut() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(adapter::serverToProxyResponseTimedOut).doesNotThrowAnyException();
  }

  @Test
  void testServerToProxyResponseReceiving() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(adapter::serverToProxyResponseReceiving).doesNotThrowAnyException();
  }

  @Test
  void testServerToProxyResponseReceived() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(adapter::serverToProxyResponseReceived).doesNotThrowAnyException();
  }

  @Test
  void testProxyToClientResponse() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);
    HttpObject input = mock(HttpObject.class);

    HttpObject result = adapter.proxyToClientResponse(input);

    assertThat(result).isSameAs(input);
  }

  @Test
  void testProxyToServerConnectionQueued() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(adapter::proxyToServerConnectionQueued).doesNotThrowAnyException();
  }

  @Test
  void testProxyToServerResolutionStarted() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    InetSocketAddress result = adapter.proxyToServerResolutionStarted("example.com:80");

    assertThat(result).isNull();
  }

  @Test
  void testProxyToServerResolutionFailed() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(() -> adapter.proxyToServerResolutionFailed("example.com:80"))
        .doesNotThrowAnyException();
  }

  @Test
  void testProxyToServerResolutionSucceeded() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);
    InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8080);

    assertThatCode(() -> adapter.proxyToServerResolutionSucceeded("example.com:80", address))
        .doesNotThrowAnyException();
  }

  @Test
  void testProxyToServerConnectionStarted() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(adapter::proxyToServerConnectionStarted).doesNotThrowAnyException();
  }

  @Test
  void testProxyToServerConnectionSSLHandshakeStarted() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(adapter::proxyToServerConnectionSSLHandshakeStarted).doesNotThrowAnyException();
  }

  @Test
  void testProxyToServerConnectionFailed() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThatCode(adapter::proxyToServerConnectionFailed).doesNotThrowAnyException();
  }

  @Test
  void testProxyToServerConnectionSucceeded() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);
    ChannelHandlerContext serverCtx = mock(ChannelHandlerContext.class);

    assertThatCode(() -> adapter.proxyToServerConnectionSucceeded(serverCtx))
        .doesNotThrowAnyException();
  }

  @Test
  void testProxyToServerAllowMitm() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    boolean result = adapter.proxyToServerAllowMitm();

    assertThat(result).isTrue();
  }

  @Test
  void testImplementsHttpFilters() {
    HttpFiltersAdapter adapter = new HttpFiltersAdapter(originalRequest, ctx);

    assertThat(adapter).isInstanceOf(HttpFilters.class);
  }
}
