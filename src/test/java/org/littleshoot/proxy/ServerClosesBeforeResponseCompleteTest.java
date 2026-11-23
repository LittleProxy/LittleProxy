package org.littleshoot.proxy;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Regression test for #854: the upstream server closes the connection after the proxy has written
 * the request, before the response is complete. The client must learn about it right away (a 502,
 * or a closed connection if the response was already under way) instead of waiting forever.
 *
 * <p>In the MITM + chained proxy tests this happened when the chained proxy rejected a missing TLS
 * 1.3 client certificate: TLS 1.3 completes the handshake on the client side before the server has
 * checked the certificate, so the proxy wrote the request and only then saw the connection close.
 */
final class ServerClosesBeforeResponseCompleteTest {

  private static final String UPSTREAM_HOST = "127.0.0.1";

  /** Short, so that a regression fails with a read timeout instead of hanging the build. */
  private static final RequestConfig SHORT_TIMEOUT =
      RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(5000).build();

  /** What the upstream server writes after reading the request, before it closes the connection. */
  private final AtomicReference<String> responseBeforeClose = new AtomicReference<>("");

  /**
   * How many requests reached the upstream server. Without it, a 502 because the proxy could not
   * reach the upstream at all would look like a pass.
   */
  private final AtomicInteger requestsReceived = new AtomicInteger();

  private ServerSocket upstream;
  private HttpProxyServer proxyServer;

  @BeforeEach
  void setUp() throws IOException {
    // Bind to the same address upstreamHost() targets: getLoopbackAddress() may return ::1.
    upstream = new ServerSocket(0, 50, InetAddress.getByName(UPSTREAM_HOST));
    Thread acceptor = new Thread(this::serveAndClose, "closing-upstream");
    acceptor.setDaemon(true);
    acceptor.start();
    proxyServer = DefaultHttpProxyServer.bootstrap().withPort(0).start();
  }

  @AfterEach
  void tearDown() throws IOException {
    if (proxyServer != null) {
      proxyServer.abort();
    }
    upstream.close();
  }

  @Test
  void respondsBadGatewayWhenServerClosesWithoutResponding() throws IOException {
    try (CloseableHttpClient client = proxiedClient()) {
      HttpResponse response = client.execute(upstreamHost(), get());

      assertThat(requestsReceived.get())
          .as("the request must reach the upstream, so the 502 is for its early close")
          .isEqualTo(1);
      assertThat(response.getStatusLine().getStatusCode()).isEqualTo(502);
      EntityUtils.consume(response.getEntity());
    }
  }

  @Test
  void closesClientConnectionWhenServerClosesInTheMiddleOfTheResponse() throws IOException {
    responseBeforeClose.set("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n");

    try (CloseableHttpClient client = proxiedClient()) {
      HttpResponse response = client.execute(upstreamHost(), get());
      assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);

      assertThatThrownBy(() -> EntityUtils.toString(response.getEntity()))
          .as("a truncated response must end with a closed connection, not a stalled one")
          .isInstanceOf(IOException.class)
          .isNotInstanceOf(SocketTimeoutException.class);
    }
  }

  private CloseableHttpClient proxiedClient() {
    return TestUtils.buildHttpClient(
        true, false, proxyServer.getListenAddress().getPort(), null, null);
  }

  private HttpHost upstreamHost() {
    return new HttpHost(UPSTREAM_HOST, upstream.getLocalPort());
  }

  private static HttpGet get() {
    HttpGet request = new HttpGet("/");
    request.setConfig(SHORT_TIMEOUT);
    return request;
  }

  /** Reads each request's headers, writes {@link #responseBeforeClose} and closes. */
  private void serveAndClose() {
    while (!upstream.isClosed()) {
      try (Socket socket = upstream.accept()) {
        readHeaders(socket.getInputStream());
        requestsReceived.incrementAndGet();
        OutputStream out = socket.getOutputStream();
        out.write(responseBeforeClose.get().getBytes(US_ASCII));
        out.flush();
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
