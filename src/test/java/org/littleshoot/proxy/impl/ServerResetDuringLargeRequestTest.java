package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;

/**
 * An upstream that drops the connection while a large aggregated request body is still being
 * written must produce a response for the client, and must not leave the client connection unable
 * to send its next request.
 */
class ServerResetDuringLargeRequestTest {
  private static final int BODY_BYTES = 16 * 1024 * 1024;
  private static final String EARLY_RESPONSE =
      "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

  private ServerSocket upstream;
  private HttpProxyServer proxy;

  @AfterEach
  void tearDown() throws IOException {
    if (proxy != null) {
      proxy.abort();
    }
    if (upstream != null) {
      upstream.close();
    }
  }

  @RepeatedTest(5)
  void serverResetWithoutResponseReturnsBadGatewayAndKeepsConnectionUsable() throws IOException {
    start(false);
    try (Socket client = connectClient()) {
      assertThat(sendLargeRequest(client)).startsWith("HTTP/1.1 502");
      assertThat(sendLargeRequest(client)).startsWith("HTTP/1.1 502");
    }
  }

  @RepeatedTest(5)
  void serverEarlyResponseThenResetKeepsConnectionUsable() throws IOException {
    start(true);
    try (Socket client = connectClient()) {
      // The kernel may discard the early response when the reset arrives, so either status is
      // acceptable.
      assertThat(sendLargeRequest(client)).matches("HTTP/1\\.1 (400|502) .*");
      assertThat(sendLargeRequest(client)).matches("HTTP/1\\.1 (400|502) .*");
    }
  }

  private void start(boolean sendEarlyResponse) throws IOException {
    upstream = new ServerSocket(0);
    Thread upstreamThread =
        new Thread(() -> resetAfterPartialRead(sendEarlyResponse), "reset-upstream");
    upstreamThread.setDaemon(true);
    upstreamThread.start();
    proxy =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withFiltersSource(
                new HttpFiltersSourceAdapter() {
                  @Override
                  public int getMaximumRequestBufferSizeInBytes() {
                    return 2 * BODY_BYTES;
                  }
                })
            .start();
  }

  private Socket connectClient() throws IOException {
    Socket client = new Socket();
    client.connect(new InetSocketAddress("127.0.0.1", proxy.getListenAddress().getPort()));
    client.setSoTimeout(15_000);
    return client;
  }

  private String sendLargeRequest(Socket client) throws IOException {
    OutputStream out = client.getOutputStream();
    String target = "127.0.0.1:" + upstream.getLocalPort();
    out.write(
        ("POST http://"
                + target
                + "/upload HTTP/1.1\r\nHost: "
                + target
                + "\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                + BODY_BYTES
                + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    byte[] chunk = new byte[64 * 1024];
    Arrays.fill(chunk, (byte) 'a');
    for (int written = 0; written < BODY_BYTES; written += chunk.length) {
      out.write(chunk);
    }
    out.flush();
    return readResponse(client.getInputStream());
  }

  private static String readResponse(InputStream in) throws IOException {
    String statusLine = readLine(in);
    int contentLength = 0;
    for (String header = readLine(in); !header.isEmpty(); header = readLine(in)) {
      String lower = header.toLowerCase(Locale.ROOT);
      if (lower.startsWith("content-length:")) {
        contentLength = Integer.parseInt(lower.substring("content-length:".length()).trim());
      }
    }
    for (int i = 0; i < contentLength; i++) {
      if (in.read() < 0) {
        break;
      }
    }
    return statusLine;
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    for (int b = in.read(); b != '\n'; b = in.read()) {
      if (b < 0) {
        throw new IOException("Connection closed before a complete response line");
      }
      if (b != '\r') {
        line.write(b);
      }
    }
    return line.toString(StandardCharsets.US_ASCII);
  }

  private void resetAfterPartialRead(boolean sendEarlyResponse) {
    while (!upstream.isClosed()) {
      try (Socket socket = upstream.accept()) {
        InputStream in = socket.getInputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        while (total < 64 * 1024) {
          int n = in.read(buf);
          if (n < 0) {
            break;
          }
          total += n;
        }
        if (sendEarlyResponse) {
          socket.getOutputStream().write(EARLY_RESPONSE.getBytes(StandardCharsets.US_ASCII));
          socket.getOutputStream().flush();
        }
        socket.setSoLinger(true, 0);
      } catch (IOException e) {
        if (upstream.isClosed()) {
          return;
        }
      }
    }
  }
}
