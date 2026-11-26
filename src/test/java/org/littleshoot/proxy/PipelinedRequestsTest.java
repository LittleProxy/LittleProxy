package org.littleshoot.proxy;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * A client pipelines requests over one connection: it sends the next request before it has the
 * previous response. Over a non-pooled server connection, each response must still be decoded and
 * filtered according to the request it answers, not the request written after it.
 */
@Timeout(30)
final class PipelinedRequestsTest {

  private static final String UPSTREAM_HOST = "127.0.0.1";

  /** How long the upstream waits for the second request before answering anyway. */
  private static final int WAIT_FOR_SECOND_REQUEST_MILLIS = 2000;

  private static final String GET_BODY = "hello";

  /** The HEAD response advertises the length of a body it doesn't send. */
  private static final int HEAD_CONTENT_LENGTH = 100;

  /** The path of the request whose filters each {@code serverToProxyResponseReceived} reached. */
  private final Queue<String> responsesReceived = new ConcurrentLinkedQueue<>();

  private final CountDownLatch allResponsesReceived = new CountDownLatch(2);

  /** Set once the upstream has read both requests before answering the first one. */
  private volatile boolean upstreamSawBothRequestsFirst;

  private ServerSocket upstream;
  @Nullable private HttpProxyServer proxyServer;

  @BeforeEach
  void setUp() throws IOException {
    // Bind to the same address the requests target: getLoopbackAddress() may return ::1.
    upstream = new ServerSocket(0, 50, InetAddress.getByName(UPSTREAM_HOST));
    Thread acceptor = new Thread(this::serve, "pipelining-upstream");
    acceptor.setDaemon(true);
    acceptor.start();
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withFiltersSource(new RecordResponseCompletion())
            .start();
  }

  @AfterEach
  void tearDown() throws IOException {
    if (proxyServer != null) {
      proxyServer.abort();
    }
    upstream.close();
  }

  @ParameterizedTest(name = "{0} /a, then {1} /b")
  @CsvSource({"GET, HEAD", "HEAD, GET"})
  void pipelinedRequestsGetTheirOwnResponsesIntact(String firstMethod, String secondMethod)
      throws IOException, InterruptedException {
    String target = "http://" + UPSTREAM_HOST + ":" + upstream.getLocalPort();
    String host = UPSTREAM_HOST + ":" + upstream.getLocalPort();
    try (Socket client = new Socket(UPSTREAM_HOST, proxyServer.getListenAddress().getPort())) {
      client.setSoTimeout(5000);
      OutputStream out = client.getOutputStream();
      out.write(
          (request(firstMethod, target + "/a", host) + request(secondMethod, target + "/b", host))
              .getBytes(US_ASCII));
      out.flush();

      InputStream in = client.getInputStream();
      assertResponse(readResponse(in, firstMethod), firstMethod + " /a");
      assertResponse(readResponse(in, secondMethod), secondMethod + " /b");
    }
    assertThat(upstreamSawBothRequestsFirst)
        .as("the proxy must forward the second request before the first is answered")
        .isTrue();
    assertThat(allResponsesReceived.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responsesReceived)
        .as("each response must complete on the filters of the request it answers")
        .containsExactly("/a", "/b");
  }

  private static String request(String method, String uri, String host) {
    return method + " " + uri + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n";
  }

  private static void assertResponse(Response response, String request) {
    assertThat(response.statusLine).as("response to " + request).contains(" 200 ");
    if (request.startsWith("HEAD")) {
      assertThat(response.headers)
          .as("headers of " + request)
          .containsIgnoringCase("content-length: " + HEAD_CONTENT_LENGTH);
    } else {
      assertThat(response.body).as("body of " + request).isEqualTo(GET_BODY);
    }
  }

  /**
   * Reads both requests (or gives up waiting for the second one), then answers them in order: a GET
   * with a body, a HEAD with only headers, advertising a longer body than a GET's.
   */
  private void serve() {
    while (!upstream.isClosed()) {
      try (Socket socket = upstream.accept()) {
        InputStream in = socket.getInputStream();
        String firstRequest = readHeaders(in);
        if (firstRequest == null) {
          continue;
        }
        socket.setSoTimeout(WAIT_FOR_SECOND_REQUEST_MILLIS);
        String secondRequest;
        try {
          secondRequest = readHeaders(in);
        } catch (SocketTimeoutException e) {
          secondRequest = null;
        }
        upstreamSawBothRequestsFirst = secondRequest != null;
        socket.setSoTimeout(0);

        OutputStream out = socket.getOutputStream();
        out.write(responseTo(firstRequest));
        if (secondRequest == null) {
          // The proxy is waiting for the first response before it forwards the second request.
          out.flush();
          secondRequest = readHeaders(in);
        }
        if (secondRequest != null) {
          out.write(responseTo(secondRequest));
        }
        out.flush();
        while (in.read() >= 0) {
          // keep the connection open until the proxy closes it
        }
      } catch (IOException e) {
        if (upstream.isClosed()) {
          return;
        }
      }
    }
  }

  private static byte[] responseTo(String request) {
    String response =
        request.startsWith("HEAD ")
            ? "HTTP/1.1 200 OK\r\nContent-Length: " + HEAD_CONTENT_LENGTH + "\r\n\r\n"
            : "HTTP/1.1 200 OK\r\nContent-Length: " + GET_BODY.length() + "\r\n\r\n" + GET_BODY;
    return response.getBytes(US_ASCII);
  }

  /** Reads one request's or response's headers, or returns null at the end of the stream. */
  private static @Nullable String readHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    int matched = 0;
    byte[] end = "\r\n\r\n".getBytes(US_ASCII);
    while (matched < end.length) {
      int b = in.read();
      if (b < 0) {
        return null;
      }
      headers.write(b);
      matched = b == end[matched] ? matched + 1 : (b == end[0] ? 1 : 0);
    }
    return headers.toString(US_ASCII.name());
  }

  private static Response readResponse(InputStream in, String method) throws IOException {
    String headers = readHeaders(in);
    assertThat(headers).as("response headers").isNotNull();
    String statusLine = headers.substring(0, headers.indexOf("\r\n"));
    StringBuilder body = new StringBuilder();
    if (!"HEAD".equals(method)) {
      int length = contentLength(headers);
      for (int i = 0; i < length; i++) {
        int b = in.read();
        if (b < 0) {
          break;
        }
        body.append((char) b);
      }
    }
    return new Response(statusLine, headers, body.toString());
  }

  private static int contentLength(String headers) {
    for (String line : headers.split("\r\n")) {
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
        return Integer.parseInt(line.substring(colon + 1).trim());
      }
    }
    return 0;
  }

  private static final class Response {
    final String statusLine;
    final String headers;
    final String body;

    Response(String statusLine, String headers, String body) {
      this.statusLine = statusLine;
      this.headers = headers;
      this.body = body;
    }
  }

  private final class RecordResponseCompletion extends HttpFiltersSourceAdapter {
    @Override
    public HttpFilters filterRequest(HttpRequest originalRequest) {
      String path = URI.create(originalRequest.uri()).getPath();
      return new HttpFiltersAdapter(originalRequest) {
        @Override
        public void serverToProxyResponseReceived() {
          responsesReceived.add(path);
          allResponsesReceived.countDown();
        }
      };
    }
  }
}
