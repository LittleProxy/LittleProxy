package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.ThreadPoolConfiguration;

/**
 * End-to-end regression test for <a
 * href="https://github.com/LittleProxy/LittleProxy/issues/827">issue #827</a>.
 *
 * <p>A chunked POST is sent through a chained SOCKS5 proxy that deliberately takes a long time to
 * establish the upstream connection. While that connection is being established the client's body
 * chunks must not block the client event loop: an unrelated request handled by the same
 * single-threaded client event loop must still be answered promptly. The chunked body must also
 * arrive at the origin in full once the connection is ready.
 */
final class SlowConnectDoesNotBlockEventLoopTest {

  /** How long the fake chained proxy stalls before completing the SOCKS5 handshake. */
  private static final int SLOW_GREETING_DELAY_MILLIS = 3000;

  /** Wait before issuing the observer request, so the chunked POST is mid-establishment. */
  private static final int OBSERVER_REQUEST_DELAY_MILLIS = 500;

  /** The observer request must complete well before the slow connection is established. */
  private static final long OBSERVER_LATENCY_BUDGET_MILLIS = 1500;

  @Test
  void writesDuringASlowConnectDoNotBlockOtherClientsOnTheSameEventLoop() throws Exception {
    Server webServer = TestUtils.startWebServer(false, "target/littleproxy_keystore.jks");
    int webPort = TestUtils.findLocalHttpPort(webServer);
    try (SlowSocks5Origin slowOrigin = new SlowSocks5Origin(SLOW_GREETING_DELAY_MILLIS)) {
      slowOrigin.start();

      // A single client-to-proxy worker thread so both client connections share one event loop.
      ThreadPoolConfiguration threadPool = new ThreadPoolConfiguration();
      threadPool.withClientToProxyWorkerThreads(1);

      HttpProxyServer proxy =
          DefaultHttpProxyServer.bootstrap()
              .withName("slow-connect")
              .withPort(0)
              .withThreadPoolConfiguration(threadPool)
              .withChainProxyManager(
                  (httpRequest, chainedProxies, details) -> {
                    if (httpRequest.uri().contains("/slow")) {
                      chainedProxies.add(
                          new ChainedProxyAdapter() {
                            @Override
                            public InetSocketAddress getChainedProxyAddress() {
                              return new InetSocketAddress("127.0.0.1", slowOrigin.port());
                            }

                            @Override
                            public ChainedProxyType getChainedProxyType() {
                              return ChainedProxyType.SOCKS5;
                            }
                          });
                    } else {
                      chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
                    }
                  })
              .start();

      try {
        byte[] body = new byte[64 * 1024];

        // Client A: chunked POST through the slow chained proxy.
        CompletableFuture<String> slowResponse = new CompletableFuture<>();
        Thread slowClient = new Thread(() -> runChunkedPost(proxy, webPort, body, slowResponse));
        slowClient.setDaemon(true);
        slowClient.start();

        // Let client A reach the slow connection establishment so its write is in flight.
        Thread.sleep(OBSERVER_REQUEST_DELAY_MILLIS);

        // Client B: a plain, directly-connected request on the same single client event loop.
        long startNanos = System.nanoTime();
        String fastResponse = runDirectGet(proxy, webPort);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertThat(fastResponse).isEqualTo("Received 0 bytes\n");
        assertThat(elapsedMillis)
            .as(
                "an unrelated request must not be blocked by a write pending on a slow connection"
                    + " establishment (elapsed=%dms)",
                elapsedMillis)
            .isLessThan(OBSERVER_LATENCY_BUDGET_MILLIS);

        assertThat(slowResponse.get(30, TimeUnit.SECONDS))
            .as("the chunked body must be delivered in full once the connection is established")
            .isEqualTo("Received " + body.length + " bytes\n");
      } finally {
        proxy.abort();
      }
    } finally {
      webServer.stop();
    }
  }

  private static void runChunkedPost(
      HttpProxyServer proxy, int webPort, byte[] body, CompletableFuture<String> result) {
    try (CloseableHttpClient httpClient =
        TestUtils.buildHttpClient(true, true, proxy.getListenAddress().getPort(), null, null)) {
      HttpPost request = new HttpPost("/slow/upload");
      request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
      ByteArrayEntity entity = new ByteArrayEntity(body);
      entity.setChunked(true);
      request.setEntity(entity);
      HttpResponse response = httpClient.execute(new HttpHost("127.0.0.1", webPort), request);
      result.complete(EntityUtils.toString(response.getEntity()));
    } catch (Exception e) {
      result.completeExceptionally(e);
    }
  }

  private static String runDirectGet(HttpProxyServer proxy, int webPort) throws IOException {
    try (CloseableHttpClient httpClient =
        TestUtils.buildHttpClient(true, true, proxy.getListenAddress().getPort(), null, null)) {
      HttpGet request = new HttpGet("/fast");
      request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
      HttpResponse response = httpClient.execute(new HttpHost("127.0.0.1", webPort), request);
      return EntityUtils.toString(response.getEntity());
    }
  }

  /**
   * A minimal SOCKS5 server that stalls before completing the handshake (to simulate a slow chained
   * connection) and then answers the tunnelled HTTP request directly. It counts the bytes of the
   * chunked request body and replies with {@code Received <n> bytes}, matching {@link
   * TestUtils#startWebServer}.
   */
  private static final class SlowSocks5Origin implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final int greetingDelayMillis;
    private volatile Thread acceptThread;

    SlowSocks5Origin(int greetingDelayMillis) throws IOException {
      this.serverSocket = new ServerSocket(0, 50);
      this.greetingDelayMillis = greetingDelayMillis;
    }

    int port() {
      return serverSocket.getLocalPort();
    }

    void start() {
      acceptThread = new Thread(this::acceptLoop, "slow-socks5-origin");
      acceptThread.setDaemon(true);
      acceptThread.start();
    }

    private void acceptLoop() {
      while (!serverSocket.isClosed()) {
        try (Socket socket = serverSocket.accept()) {
          handle(socket);
        } catch (IOException e) {
          if (!serverSocket.isClosed()) {
            throw new RuntimeException(e);
          }
        }
      }
    }

    private void handle(Socket socket) throws IOException {
      DataInputStream in =
          new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream()));
      OutputStream out = socket.getOutputStream();

      int version = in.readUnsignedByte();
      int methodCount = in.readUnsignedByte();
      in.readFully(new byte[methodCount]);
      if (version != 5) {
        throw new IOException("Unexpected SOCKS version " + version);
      }

      // Stall before completing the handshake: the proxy stays in its connecting state.
      sleep(greetingDelayMillis);
      out.write(new byte[] {5, 0}); // NO_AUTH
      out.flush();

      // CONNECT request: version, command, reserved, address type, address, port.
      in.readUnsignedByte();
      in.readUnsignedByte();
      in.readUnsignedByte();
      int addressType = in.readUnsignedByte();
      switch (addressType) {
        case 1:
          in.readFully(new byte[4]);
          break;
        case 3:
          int hostLength = in.readUnsignedByte();
          in.readFully(new byte[hostLength]);
          break;
        case 4:
          in.readFully(new byte[16]);
          break;
        default:
          throw new IOException("Unexpected SOCKS address type " + addressType);
      }
      in.readUnsignedShort();
      out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); // succeeded, 0.0.0.0:0
      out.flush();

      long bodyBytes = readChunkedRequestBody(in);

      byte[] content = ("Received " + bodyBytes + " bytes\n").getBytes(StandardCharsets.US_ASCII);
      out.write(
          ("HTTP/1.1 200 OK\r\nContent-Length: " + content.length + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.write(content);
      out.flush();
    }

    /** Reads HTTP headers, then the chunked body, returning the number of body bytes. */
    private static long readChunkedRequestBody(DataInputStream in) throws IOException {
      String line;
      while (!(line = readLine(in)).isEmpty()) {
        // Skip request line and headers.
      }

      long bodyBytes = 0;
      while (true) {
        String sizeLine = readLine(in);
        int semicolon = sizeLine.indexOf(';');
        String sizeHex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
        int chunkSize = Integer.parseInt(sizeHex, 16);
        if (chunkSize == 0) {
          // Consume any trailers up to the terminating empty line.
          while (!readLine(in).isEmpty()) {
            // skip trailer
          }
          return bodyBytes;
        }
        readFully(in, chunkSize);
        bodyBytes += chunkSize;
        readLine(in); // CRLF after the chunk data
      }
    }

    private static String readLine(DataInputStream in) throws IOException {
      StringBuilder line = new StringBuilder();
      int previous = -1;
      int current;
      while ((current = in.read()) != -1) {
        if (previous == '\r' && current == '\n') {
          line.setLength(line.length() - 1); // drop the '\r'
          return line.toString();
        }
        line.append((char) current);
        previous = current;
      }
      throw new IOException("Unexpected end of stream while reading a line");
    }

    private static void readFully(DataInputStream in, int count) throws IOException {
      byte[] buffer = new byte[count];
      in.readFully(buffer);
    }

    private static void sleep(int millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void close() throws IOException {
      serverSocket.close();
      Thread thread = acceptThread;
      if (thread != null) {
        try {
          thread.join(5000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
