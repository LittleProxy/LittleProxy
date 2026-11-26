package org.littleshoot.proxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.netty.handler.codec.http.HttpRequest;
import java.io.IOException;
import java.net.URI;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.littleshoot.proxy.extras.TestMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.SlowEmptyQueueServerConnectionPool;

/**
 * Regression test for #859, the pooled counterpart of {@link KeepAliveNextRequestTest}: with MITM
 * over the shared pool, a client's server connection stays attached to its session, so the client
 * can write its next request while the proxy is still completing the previous response. If the
 * request arrives after the proxy found no pending request but before it cleared the current one,
 * the next response must still be read in full.
 *
 * <p>{@link SlowEmptyQueueServerConnectionPool} pauses right after finding no pending request,
 * which puts the client's next request inside that window.
 */
@Tag("slow-test")
@Timeout(30)
final class PooledKeepAliveNextRequestTest {

  private static final String[] PATHS = {"/first", "/second"};

  /** Short, so that a regression fails with a read timeout instead of hanging the build. */
  private static final RequestConfig SHORT_TIMEOUT =
      RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(5000).build();

  private final WireMockServer mockServer =
      new WireMockServer(options().dynamicPort().dynamicHttpsPort());

  /** The path of the request whose filters each {@code serverToProxyResponseReceived} reached. */
  private final Queue<String> responsesReceived = new ConcurrentLinkedQueue<>();

  /**
   * The client can have a whole response before the proxy calls {@code
   * serverToProxyResponseReceived} for it.
   */
  private final CountDownLatch allResponsesReceived = new CountDownLatch(PATHS.length);

  @Nullable private HttpProxyServer proxyServer;

  @AfterEach
  void tearDown() {
    try {
      if (proxyServer != null) {
        proxyServer.abort();
      }
    } finally {
      mockServer.stop();
    }
  }

  @Test
  void nextRequestWrittenWhileTheResponseCompletesGetsItsFullResponse()
      throws IOException, InterruptedException {
    mockServer.start();
    for (String path : PATHS) {
      mockServer.stubFor(
          get(urlEqualTo(path))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Length", Integer.toString(body(path).length()))
                      .withBody(body(path))));
    }
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withManInTheMiddle(new TestMitmManager())
            .withSharedServerConnectionPool(true)
            .withServerConnectionPoolName(SlowEmptyQueueServerConnectionPool.NAME)
            .withPoolSharedMitmConnections(true)
            .withFiltersSource(new RecordResponseCompletion())
            .start();

    HttpHost target = new HttpHost("localhost", mockServer.httpsPort(), "https");
    try (CloseableHttpClient client =
        TestUtils.buildHttpClient(
            true, true, proxyServer.getListenAddress().getPort(), null, null)) {
      for (String path : PATHS) {
        HttpGet request = new HttpGet(path);
        request.setConfig(SHORT_TIMEOUT);

        HttpResponse response = client.execute(target, request);

        assertThat(response.getStatusLine().getStatusCode()).as(path).isEqualTo(200);
        assertThat(EntityUtils.toString(response.getEntity())).isEqualTo(body(path));
      }
    }
    assertThat(
            ((DefaultHttpProxyServer) proxyServer)
                .getServerConnectionPool()
                .getMetrics()
                .getTotalConnections())
        .as("both requests must go over the same server connection")
        .isEqualTo(1);
    assertThat(allResponsesReceived.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responsesReceived).containsExactly(PATHS);
  }

  private static String body(String path) {
    return "body of " + path;
  }

  private final class RecordResponseCompletion extends HttpFiltersSourceAdapter {
    @Override
    public HttpFilters filterRequest(HttpRequest originalRequest) {
      String path = URI.create(originalRequest.uri()).getPath();
      return new HttpFiltersAdapter(originalRequest) {
        @Override
        public void serverToProxyResponseReceived() {
          // The CONNECT request's filters also see the tunnel's 200 response.
          if (!"CONNECT".equals(originalRequest.method().name())) {
            responsesReceived.add(path);
            allResponsesReceived.countDown();
          }
        }
      };
    }
  }
}
