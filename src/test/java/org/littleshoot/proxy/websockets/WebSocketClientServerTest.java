package org.littleshoot.proxy.websockets;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.*;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.HttpProxyServerBootstrap;
import org.littleshoot.proxy.extras.TestMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("slow-test")
@EnableThreadDump
public final class WebSocketClientServerTest {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_CONNECTION_ATTEMPTS = 5;
  private static final long TEST_TIMEOUT_SECONDS = 60L;
  private static final Logger logger = LoggerFactory.getLogger(WebSocketClientServerTest.class);
  private HttpProxyServer proxy;
  private final WebSocketServer server = new WebSocketServer();
  private final WebSocketClient client = new WebSocketClient();

  @AfterEach
  void tearDown() throws Exception {
    client.close();
    server.stop();
    if (proxy != null) {
      proxy.stop();
      proxy = null;
    }
  }

  private void startProxy(final boolean withSsl) {
    final HttpProxyServerBootstrap bootstrap =
        DefaultHttpProxyServer.bootstrap().withTransparent(true).withPort(0);
    if (withSsl) {
      bootstrap.withManInTheMiddle(new TestMitmManager());
    }
    proxy = bootstrap.start();
  }

  @Disabled("Only useful for debugging issues with the proxy tests")
  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  public void directInsecureConnection() throws Exception {
    testIntegration(false);
  }

  @Disabled("Only useful for debugging issues with the proxy tests")
  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  public void directSecureConnection() throws Exception {
    testIntegration(true);
  }

  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  public void proxiedInsecureConnectionWsScheme() throws Exception {
    testIntegration(false, true, "ws");
  }

  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  public void proxiedInsecureConnectionHttpScheme() throws Exception {
    testIntegration(false, true, "http");
  }

  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  public void proxiedSecureConnectionWssScheme() throws Exception {
    testIntegration(true, true, "wss");
  }

  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  public void proxiedSecureConnectionHttpsScheme() throws Exception {
    testIntegration(true, true, "https");
  }

  private void testIntegration(final boolean withSsl) throws Exception {
    testIntegration(withSsl, false, withSsl ? "wss" : "ws");
  }

  private void testIntegration(final boolean withSsl, final boolean withProxy, final String scheme)
      throws Exception {
    final InetSocketAddress serverAddress = server.start(withSsl, CONNECT_TIMEOUT);
    if (withProxy) {
      startProxy(withSsl);
    }

    final URI serverUri =
        URI.create(
            scheme
                + "://"
                + serverAddress.getHostString()
                + ":"
                + serverAddress.getPort()
                + WebSocketServer.WEBSOCKET_PATH);

    openClient(serverUri, withProxy);

    final String request = "test 1 test 2 test 3 test 4";
    assertThat(client.send(request).awaitUninterruptibly(RESPONSE_TIMEOUT.toMillis()))
        .as("Timed out waiting for message to be sent after %s s.", RESPONSE_TIMEOUT)
        .isTrue();
    final String response = client.waitForResponse(RESPONSE_TIMEOUT);
    assertThat(response).isEqualTo(request.toUpperCase());
  }

  private void openClient(final URI uri, final boolean withProxy) throws InterruptedException {
    final Optional<InetSocketAddress> proxyAddress =
        Optional.ofNullable(proxy)
            .filter(httpProxy -> withProxy)
            .map(HttpProxyServer::getListenAddress);
    int connectionAttempt = 0;
    boolean connected = false;
    while (!connected && connectionAttempt++ < MAX_CONNECTION_ATTEMPTS) {
      try {
        client.open(uri, CONNECT_TIMEOUT, proxyAddress);
        connected = true;
      } catch (TimeoutException e) {
        logger.warn(
            "Connection attempt {} of {} : {}",
            connectionAttempt,
            MAX_CONNECTION_ATTEMPTS,
            e.getMessage(),
            e);
        Thread.sleep(CONNECT_TIMEOUT.toMillis() / 2);
      }
    }
    assertThat(connected)
        .as("Connection timed out after " + MAX_CONNECTION_ATTEMPTS + " attempts")
        .isTrue();
  }
}
