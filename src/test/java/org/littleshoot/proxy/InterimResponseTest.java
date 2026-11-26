package org.littleshoot.proxy;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * A 1xx interim response (such as {@code 100 Continue}) does not answer the request: the final
 * response follows it on the same connection. The proxy must keep treating the request as
 * unanswered, so that it still parses the final response's body and completes it on the request's
 * filters.
 */
@Timeout(20)
final class InterimResponseTest {

  private static final String UPSTREAM_HOST = "127.0.0.1";

  /** Short, so that a regression fails with a read timeout instead of hanging the build. */
  private static final RequestConfig SHORT_TIMEOUT =
      RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(5000).build();

  /** The request URI each {@code serverToProxyResponseReceived} was called for. */
  private final Queue<String> responsesReceived = new ConcurrentLinkedQueue<>();

  private ServerSocket upstream;
  @Nullable private HttpProxyServer proxyServer;

  @BeforeEach
  void setUp() throws IOException {
    // Bind to the same address upstreamHost() targets: getLoopbackAddress() may return ::1.
    upstream = new ServerSocket(0, 50, InetAddress.getByName(UPSTREAM_HOST));
    Thread acceptor = new Thread(this::serve, "interim-response-upstream");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    if (proxyServer != null) {
      proxyServer.abort();
    }
    upstream.close();
  }

  @Test
  void finalResponseAfterInterimResponseKeepsItsBody() throws IOException {
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withFiltersSource(
                new HttpFiltersSourceAdapter() {
                  @Override
                  public HttpFilters filterRequest(HttpRequest originalRequest) {
                    String uri = originalRequest.uri();
                    return new HttpFiltersAdapter(originalRequest) {
                      @Override
                      public void serverToProxyResponseReceived() {
                        responsesReceived.add(uri);
                      }

                      @Override
                      public HttpObject serverToProxyResponse(HttpObject httpObject) {
                        return httpObject;
                      }
                    };
                  }
                })
            .start();

    try (CloseableHttpClient client =
        TestUtils.buildHttpClient(
            true, false, proxyServer.getListenAddress().getPort(), null, null)) {
      HttpGet request = new HttpGet("/interim");
      request.setConfig(SHORT_TIMEOUT);
      HttpResponse response =
          client.execute(new HttpHost(UPSTREAM_HOST, upstream.getLocalPort()), request);

      assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
      assertThat(EntityUtils.toString(response.getEntity())).isEqualTo("hello");
    }
    assertThat(responsesReceived)
        .as("only the final response completes the request, and on the request's own filters")
        .hasSize(1)
        .allSatisfy(uri -> assertThat(uri).endsWith("/interim"));
  }

  /** Answers each request with {@code 100 Continue} followed by the final response. */
  private void serve() {
    while (!upstream.isClosed()) {
      try (Socket socket = upstream.accept()) {
        InputStream in = socket.getInputStream();
        readHeaders(in);
        OutputStream out = socket.getOutputStream();
        out.write(
            ("HTTP/1.1 100 Continue\r\n\r\n" + "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
                .getBytes(US_ASCII));
        out.flush();
        // Keep the connection open until the proxy closes it, so that the end of the body is
        // marked only by Content-Length.
        while (in.read() >= 0) {
          // discard
        }
      } catch (IOException e) {
        if (upstream.isClosed()) {
          return;
        }
      }
    }
  }

  private static void readHeaders(InputStream in) throws IOException {
    int matched = 0;
    byte[] end = "\r\n\r\n".getBytes(US_ASCII);
    while (matched < end.length) {
      int b = in.read();
      if (b < 0) {
        return;
      }
      matched = b == end[matched] ? matched + 1 : (b == end[0] ? 1 : 0);
    }
  }
}
