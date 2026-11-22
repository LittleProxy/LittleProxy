package org.littleshoot.proxy;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * When request aggregation is on, {@code ClientToProxyConnection} keeps a copy of every request
 * (backed by a pooled {@code ByteBuf}) and hands it to {@link HttpFiltersSource#filterRequest} as
 * {@code originalRequest}. The copy must be released once the request is done, even when no HTTP
 * response ever passes through the proxy.
 *
 * @see <a href="https://github.com/LittleProxy/LittleProxy/issues/152">issue 152</a>
 */
final class CopiedRequestReleaseTest {
  private static final long TIMEOUT_MS = 5000;

  private final List<HttpRequest> originalRequests = new CopyOnWriteArrayList<>();
  private final SilentServer silentServer = new SilentServer();
  private final HttpProxyServer proxyServer =
      DefaultHttpProxyServer.bootstrap()
          .withPort(0)
          .withFiltersSource(new CapturingFiltersSource(originalRequests))
          .start();

  @AfterEach
  void tearDown() throws IOException {
    try {
      proxyServer.abort();
    } finally {
      silentServer.close();
    }
  }

  @Test
  void copiedConnectRequestIsReleasedWhenTunnelIsEstablished() throws Exception {
    try (Socket client = connectToProxy()) {
      send(client, "CONNECT localhost:" + silentServer.port() + " HTTP/1.1\r\n\r\n");

      assertThat(readStatusLine(client)).isEqualTo("HTTP/1.1 200 Connection established");
      assertCopiedRequestIsReleased();
    }
  }

  @Test
  void copiedRequestIsReleasedWhenClientDisconnectsBeforeResponse() throws Exception {
    try (Socket client = connectToProxy()) {
      send(
          client,
          "GET http://localhost:"
              + silentServer.port()
              + "/never-responds HTTP/1.1\r\nHost: localhost\r\n\r\n");
      assertThat(silentServer.awaitRequest()).isTrue();
    }

    assertCopiedRequestIsReleased();
  }

  private Socket connectToProxy() throws IOException {
    Socket socket = new Socket();
    socket.connect(proxyServer.getListenAddress(), (int) TIMEOUT_MS);
    socket.setSoTimeout((int) TIMEOUT_MS);
    return socket;
  }

  private static void send(Socket socket, String request) throws IOException {
    OutputStream out = socket.getOutputStream();
    out.write(request.getBytes(US_ASCII));
    out.flush();
  }

  private static String readStatusLine(Socket socket) throws IOException {
    InputStream in = socket.getInputStream();
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = in.read()) != -1 && c != '\n') {
      if (c != '\r') {
        line.append((char) c);
      }
    }
    return line.toString();
  }

  private void assertCopiedRequestIsReleased() throws InterruptedException {
    assertThat(originalRequests).hasSize(1);
    FullHttpRequest copy = (FullHttpRequest) originalRequests.get(0);
    long deadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (copy.refCnt() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertThat(copy.refCnt()).as("refCnt of the copied request").isZero();
  }

  private static final class CapturingFiltersSource extends HttpFiltersSourceAdapter {
    private final List<HttpRequest> originalRequests;

    private CapturingFiltersSource(List<HttpRequest> originalRequests) {
      this.originalRequests = originalRequests;
    }

    @Override
    public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
      originalRequests.add(originalRequest);
      return super.filterRequest(originalRequest, ctx);
    }

    @Override
    public int getMaximumRequestBufferSizeInBytes() {
      // turns on HttpObjectAggregator, so the copied request is a FullHttpRequest with a ByteBuf
      return 1024 * 1024;
    }
  }

  /** Accepts connections and reads requests, but never writes anything back. */
  private static final class SilentServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final CountDownLatch requestReceived = new CountDownLatch(1);

    private SilentServer() {
      try {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("localhost", 0));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      Thread acceptor = new Thread(this::acceptLoop, "silent-server");
      acceptor.setDaemon(true);
      acceptor.start();
    }

    int port() {
      return serverSocket.getLocalPort();
    }

    boolean awaitRequest() throws InterruptedException {
      return requestReceived.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
      try {
        while (!serverSocket.isClosed()) {
          Socket socket = serverSocket.accept();
          accepted.add(socket);
          if (socket.getInputStream().read() != -1) {
            requestReceived.countDown();
          }
        }
      } catch (IOException ignored) {
        // server closed
      }
    }

    @Override
    public void close() throws IOException {
      serverSocket.close();
      for (Socket socket : accepted) {
        socket.close();
      }
    }
  }
}
