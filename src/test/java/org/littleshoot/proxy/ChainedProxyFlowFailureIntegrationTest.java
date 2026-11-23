package org.littleshoot.proxy;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Integration tests for the error branches of the chained-proxy connection flow steps, which are
 * private and therefore only reachable end to end:
 *
 * <ul>
 *   <li>a chained HTTP proxy answers CONNECT with a non-2xx status;
 *   <li>a chained SOCKS5 proxy offers no acceptable authentication method.
 * </ul>
 *
 * <p>In both cases the downstream proxy must fail the flow and fall back to the next chained proxy
 * (here, a direct connection), and the client request must still succeed.
 */
final class ChainedProxyFlowFailureIntegrationTest {

  private Server webServer;
  private int httpWebPort;
  private int httpsWebPort;
  private HttpProxyServer proxyServer;
  private HttpProxyServer upstreamProxy;
  private ServerSocket fakeChainedProxy;
  private final AtomicInteger attempts = new AtomicInteger();
  private final CopyOnWriteArrayList<String> connectRequests = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    webServer = TestUtils.startWebServer(true, "target/littleproxy_keystore.jks");
    httpWebPort = TestUtils.findLocalHttpPort(webServer);
    httpsWebPort = TestUtils.findLocalHttpsPort(webServer);
    fakeChainedProxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (proxyServer != null) {
      proxyServer.abort();
    }
    if (upstreamProxy != null) {
      upstreamProxy.abort();
    }
    if (fakeChainedProxy != null) {
      fakeChainedProxy.close();
    }
    if (webServer != null) {
      webServer.stop();
    }
  }

  @Test
  @DisplayName("a chained proxy that rejects CONNECT falls back to a direct connection")
  void connectRejectedByChainedProxyFallsBackToDirect() throws Exception {
    startFakeChainedProxy(
        socket -> {
          // Read the CONNECT request line and headers, then reject it.
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(socket.getInputStream(), US_ASCII));
          String requestLine = reader.readLine();
          connectRequests.add(requestLine);
          String line;
          while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // drain headers
          }
          OutputStream out = socket.getOutputStream();
          out.write(
              "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII));
          out.flush();
        });

    startProxyWithFallback(
        new ChainedProxyAdapter() {
          @Override
          public InetSocketAddress getChainedProxyAddress() {
            return new InetSocketAddress(
                InetAddress.getLoopbackAddress(), fakeChainedProxy.getLocalPort());
          }

          @Override
          public ChainedProxyType getChainedProxyType() {
            return ChainedProxyType.HTTP;
          }
        });

    HttpResponse response = httpsGet();
    assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
    assertThat(EntityUtils.toString(response.getEntity())).contains("Received");
    assertThat(attempts.get()).as("the chained proxy must have been tried first").isPositive();
    assertThat(connectRequests)
        .as("the chained proxy must have received a CONNECT request")
        .anyMatch(line -> line != null && line.startsWith("CONNECT"));
  }

  @Test
  @DisplayName("a SOCKS5 chained proxy with no acceptable auth method falls back to direct")
  void socks5NoAcceptableAuthFallsBackToDirect() throws Exception {
    startFakeChainedProxy(
        socket -> {
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          // SOCKS5 greeting: version, nmethods, methods...
          in.read(); // version
          int nmethods = in.read();
          for (int i = 0; i < nmethods; i++) {
            in.read();
          }
          // No acceptable methods.
          out.write(new byte[] {0x05, (byte) 0xFF});
          out.flush();
        });

    startProxyWithFallback(
        new ChainedProxyAdapter() {
          @Override
          public InetSocketAddress getChainedProxyAddress() {
            return new InetSocketAddress(
                InetAddress.getLoopbackAddress(), fakeChainedProxy.getLocalPort());
          }

          @Override
          public ChainedProxyType getChainedProxyType() {
            return ChainedProxyType.SOCKS5;
          }
        });

    HttpResponse response = httpsGet();
    assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
    assertThat(EntityUtils.toString(response.getEntity())).contains("Received");
    assertThat(attempts.get()).as("the chained proxy must have been tried first").isPositive();
  }

  @Test
  @DisplayName("a SOCKS5 chained proxy that rejects the password falls back to direct")
  void socks5PasswordAuthFailureFallsBackToDirect() throws Exception {
    startFakeChainedProxy(
        socket -> {
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          // Greeting: version, nmethods, methods...
          in.read();
          int nmethods = in.read();
          for (int i = 0; i < nmethods; i++) {
            in.read();
          }
          // Require username/password authentication.
          out.write(new byte[] {0x05, 0x02});
          out.flush();
          // Password auth request: version, ulen, username, plen, password.
          in.read();
          int ulen = in.read();
          for (int i = 0; i < ulen; i++) {
            in.read();
          }
          int plen = in.read();
          for (int i = 0; i < plen; i++) {
            in.read();
          }
          // Reject the credentials.
          out.write(new byte[] {0x01, 0x01});
          out.flush();
        });

    startProxyWithFallback(
        new ChainedProxyAdapter() {
          @Override
          public InetSocketAddress getChainedProxyAddress() {
            return new InetSocketAddress(
                InetAddress.getLoopbackAddress(), fakeChainedProxy.getLocalPort());
          }

          @Override
          public ChainedProxyType getChainedProxyType() {
            return ChainedProxyType.SOCKS5;
          }

          @Override
          public String getUsername() {
            return "user";
          }

          @Override
          public String getPassword() {
            return "pass";
          }
        });

    HttpResponse response = httpsGet();
    assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
    assertThat(EntityUtils.toString(response.getEntity())).contains("Received");
    assertThat(attempts.get()).as("the chained proxy must have been tried first").isPositive();
  }

  @Test
  @DisplayName("a SOCKS4 chained proxy with an unresolvable destination fails with 502")
  void socks4UnknownHostFailsWithBadGateway() throws Exception {
    startFakeChainedProxy(
        socket -> {
          // The destination cannot be resolved, so no SOCKS4 request is written.
        });

    startProxyWithFallback(
        new ChainedProxyAdapter() {
          @Override
          public InetSocketAddress getChainedProxyAddress() {
            return new InetSocketAddress(
                InetAddress.getLoopbackAddress(), fakeChainedProxy.getLocalPort());
          }

          @Override
          public ChainedProxyType getChainedProxyType() {
            return ChainedProxyType.SOCKS4;
          }
        });

    try (var client =
        TestUtils.buildHttpClient(
            true, false, proxyServer.getListenAddress().getPort(), null, null)) {
      HttpGet request = new HttpGet("http://nonexistent.invalid/");
      request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
      HttpResponse response = client.execute(new HttpHost("nonexistent.invalid", 80), request);
      assertThat(response.getStatusLine().getStatusCode()).isEqualTo(502);
    }
  }

  @Test
  @DisplayName("a chained proxy's local address is used to connect to it")
  void localAddressIsUsedForChainedConnection() throws Exception {
    upstreamProxy = DefaultHttpProxyServer.bootstrap().withPort(0).start();
    startProxyWithChainedProxy(
        new ChainedProxyAdapter() {
          @Override
          public InetSocketAddress getChainedProxyAddress() {
            return new InetSocketAddress(
                InetAddress.getLoopbackAddress(), upstreamProxy.getListenAddress().getPort());
          }

          @Override
          public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
          }

          @Override
          public ChainedProxyType getChainedProxyType() {
            return ChainedProxyType.HTTP;
          }
        });

    HttpResponse response = httpGet();
    assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName("an activity tracker that throws on requestSentToServer does not break the request")
  void throwingActivityTrackerDoesNotBreakTheRequest() throws Exception {
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .plusActivityTracker(
                new ActivityTrackerAdapter() {
                  @Override
                  public void requestSentToServer(
                      FullFlowContext flowContext, HttpRequest httpRequest) {
                    throw new IllegalStateException("boom");
                  }
                })
            .start();

    HttpResponse response = httpGet();
    assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
  }

  private void startProxyWithFallback(ChainedProxyAdapter firstProxy) {
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withChainProxyManager(
                (httpRequest, chainedProxies, clientDetails) -> {
                  chainedProxies.add(firstProxy);
                  chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
                })
            .start();
  }

  private void startProxyWithChainedProxy(ChainedProxyAdapter chainedProxy) {
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withChainProxyManager(
                (httpRequest, chainedProxies, clientDetails) -> chainedProxies.add(chainedProxy))
            .start();
  }

  private void startFakeChainedProxy(SocketHandler handler) {
    Thread acceptor =
        new Thread(
            () -> {
              while (!fakeChainedProxy.isClosed()) {
                try {
                  Socket socket = fakeChainedProxy.accept();
                  attempts.incrementAndGet();
                  try {
                    handler.handle(socket);
                  } finally {
                    socket.close();
                  }
                } catch (IOException e) {
                  return;
                }
              }
            },
            "fake-chained-proxy");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  private HttpResponse httpsGet() throws IOException {
    try (var client =
        TestUtils.buildHttpClient(
            true, true, proxyServer.getListenAddress().getPort(), null, null)) {
      HttpGet request = new HttpGet("/");
      request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
      return client.execute(new HttpHost("127.0.0.1", httpsWebPort, "https"), request);
    }
  }

  private HttpResponse httpGet() throws IOException {
    try (var client =
        TestUtils.buildHttpClient(
            true, false, proxyServer.getListenAddress().getPort(), null, null)) {
      HttpGet request = new HttpGet("/");
      request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
      return client.execute(new HttpHost("127.0.0.1", httpWebPort, "http"), request);
    }
  }

  @FunctionalInterface
  private interface SocketHandler {
    void handle(Socket socket) throws IOException;
  }
}
