package org.littleshoot.proxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Regression test for #858: a keep-alive client sends its next request as soon as it has the
 * previous response, possibly before the proxy has finished its own bookkeeping for that response.
 * The next request must still get its full response, and each response must complete on the filters
 * of the request it answers.
 */
@Timeout(20)
final class KeepAliveNextRequestTest {

  private static final String[] PATHS = {"/first", "/second"};

  /**
   * How long the proxy stalls before completing a response. Long enough for the client's next
   * request to reach the proxy within the stall.
   */
  private static final int RESPONSE_COMPLETION_DELAY_MILLIS = 500;

  /** Short, so that a regression fails with a read timeout instead of hanging the build. */
  private static final RequestConfig SHORT_TIMEOUT =
      RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(5000).build();

  private final WireMockServer mockServer = new WireMockServer(options().dynamicPort());

  /**
   * Without connection reuse there is no next request on the same server connection, and the test
   * would pass vacuously.
   */
  private final AtomicInteger serverConnections = new AtomicInteger();

  /** The path of the request whose filters each {@code serverToProxyResponseReceived} reached. */
  private final Queue<String> responsesReceived = new ConcurrentLinkedQueue<>();

  /**
   * The client can have a whole response before the proxy calls {@code
   * serverToProxyResponseReceived} for it.
   */
  private final CountDownLatch allResponsesReceived = new CountDownLatch(PATHS.length);

  @Nullable private HttpProxyServer proxyServer;

  enum ResponseBody {
    CONTENT_LENGTH {
      @Override
      ResponseDefinitionBuilder response(String body) {
        return aResponse()
            .withHeader("Content-Length", Integer.toString(body.length()))
            .withBody(body);
      }
    },
    /** WireMock uses chunked transfer encoding when the response has no Content-Length. */
    CHUNKED {
      @Override
      ResponseDefinitionBuilder response(String body) {
        return aResponse().withBody(body);
      }
    },
    /**
     * The client has the whole response from its headers, before the proxy reads the end of the
     * response.
     */
    EMPTY {
      @Override
      ResponseDefinitionBuilder response(String body) {
        return aResponse().withHeader("Content-Length", "0");
      }

      @Override
      String body(String path) {
        return "";
      }
    };

    abstract ResponseDefinitionBuilder response(String body);

    String body(String path) {
      return "body of " + path;
    }
  }

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

  @ParameterizedTest
  @EnumSource(ResponseBody.class)
  void nextRequestSentRightAfterResponseGetsItsFullResponse(ResponseBody responseBody)
      throws IOException, InterruptedException {
    start(responseBody);

    HttpHost target = new HttpHost("127.0.0.1", mockServer.port());
    try (CloseableHttpClient client =
        TestUtils.createProxiedHttpClient(proxyServer.getListenAddress().getPort())) {
      for (String path : PATHS) {
        HttpGet request = new HttpGet(path);
        request.setConfig(SHORT_TIMEOUT);

        HttpResponse response = client.execute(target, request);

        assertThat(response.getStatusLine().getStatusCode()).as(path).isEqualTo(200);
        assertThat(response.getEntity().isChunked())
            .as("chunked " + path)
            .isEqualTo(responseBody == ResponseBody.CHUNKED);
        assertThat(EntityUtils.toString(response.getEntity())).isEqualTo(responseBody.body(path));
      }
    }
    assertThat(serverConnections.get())
        .as("both requests must go over the same server connection")
        .isEqualTo(1);
    assertThat(allResponsesReceived.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responsesReceived).containsExactly(PATHS);
  }

  private void start(ResponseBody responseBody) {
    mockServer.start();
    for (String path : PATHS) {
      mockServer.stubFor(
          get(urlEqualTo(path))
              .willReturn(responseBody.response(responseBody.body(path)).withStatus(200)));
    }
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withFiltersSource(new DelayResponseCompletion())
            .plusActivityTracker(
                new ActivityTrackerAdapter() {
                  @Override
                  public void serverConnected(
                      FullFlowContext flowContext, InetSocketAddress serverAddress) {
                    serverConnections.incrementAndGet();
                  }
                })
            .start();
  }

  private final class DelayResponseCompletion extends HttpFiltersSourceAdapter {
    @Override
    public HttpFilters filterRequest(HttpRequest originalRequest) {
      String path = URI.create(originalRequest.uri()).getPath();
      return new HttpFiltersAdapter(originalRequest) {
        /** Runs before the proxy forwards the end of the response to the client. */
        @Override
        public HttpObject serverToProxyResponse(HttpObject httpObject) {
          if (httpObject instanceof LastHttpContent) {
            pause();
          }
          return httpObject;
        }

        /**
         * Runs after the proxy has forwarded the end of the response to the client, and before it
         * marks the response complete.
         */
        @Override
        public void serverToProxyResponseReceived() {
          responsesReceived.add(path);
          allResponsesReceived.countDown();
          pause();
        }
      };
    }
  }

  private static void pause() {
    try {
      Thread.sleep(RESPONSE_COMPLETION_DELAY_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
