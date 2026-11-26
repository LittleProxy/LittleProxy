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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

  @Test
  void getFollowedByPipelinedHeadGetsBothResponsesIntact()
      throws IOException, InterruptedException {
    String target = "http://" + UPSTREAM_HOST + ":" + upstream.getLocalPort();
    String host = UPSTREAM_HOST + ":" + upstream.getLocalPort();
    try (Socket client = new Socket(UPSTREAM_HOST, proxyServer.getListenAddress().getPort())) {
      client.setSoTimeout(5000);
      OutputStream out = client.getOutputStream();
      out.write(
          ("GET "
                  + target
                  + "/a HTTP/1.1\r\nHost: "
                  + host
                  + "\r\n\r\n"
                  + "HEAD "
                  + target
                  + "/b HTTP/1.1\r\nHost: "
                  + host
                  + "\r\n\r\n")
              .getBytes(US_ASCII));
      out.flush();

      InputStream in = client.getInputStream();
      Response first = readResponse(in, false);
      assertThat(first.statusLine).as("response to GET /a").contains(" 200 ");
      assertThat(first.body).as("body of GET /a").isEqualTo(GET_BODY);

      Response second = readResponse(in, true);
      assertThat(second.statusLine).as("response to HEAD /b").contains(" 200 ");
      assertThat(second.headers)
          .as("headers of HEAD /b")
          .containsIgnoringCase("content-length: " + HEAD_CONTENT_LENGTH);
    }
    assertThat(upstreamSawBothRequestsFirst)
        .as("the proxy must forward the HEAD before the GET is answered, or nothing is pipelined")
        .isTrue();
    assertThat(allResponsesReceived.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responsesReceived)
        .as("each response must complete on the filters of the request it answers")
        .containsExactly("/a", "/b");
  }

  /**
   * Reads both requests (or gives up waiting for the second one), then answers them in order: GET
   * /a with a body, HEAD /b with only headers.
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
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Length: " + GET_BODY.length() + "\r\n\r\n" + GET_BODY)
                .getBytes(US_ASCII));
        if (secondRequest == null) {
          // The proxy is waiting for the GET's response before it forwards the HEAD.
          out.flush();
          secondRequest = readHeaders(in);
        }
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Length: " + HEAD_CONTENT_LENGTH + "\r\n\r\n")
                .getBytes(US_ASCII));
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

  private static Response readResponse(InputStream in, boolean isHead) throws IOException {
    String headers = readHeaders(in);
    assertThat(headers).as("response headers").isNotNull();
    String statusLine = headers.substring(0, headers.indexOf("\r\n"));
    StringBuilder body = new StringBuilder();
    if (!isHead) {
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
