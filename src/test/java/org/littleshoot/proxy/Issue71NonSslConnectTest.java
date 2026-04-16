package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.extras.TestMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test for issue #71: Incorrect assumption that all connections made via CONNECT requests in MITM
 * mode should use SSL.
 *
 * <p>When LittleProxy handles a CONNECT request in MITM mode, it assumes that the connection to the
 * next hop should always be made over an SSL/TLS connection. Although this is true in 99.9% of
 * cases, it is not required by the HTTP spec and breaks real-world scenarios like websocket
 * connections (ws:// scheme) that use CONNECT tunnels but don't use SSL.
 */
class Issue71NonSslConnectTest {
  private static final Logger LOG = LoggerFactory.getLogger(Issue71NonSslConnectTest.class);

  private static final String DEFAULT_JKS_KEYSTORE_PATH = "target/littleproxy_keystore.jks";

  private Server webServer;
  private int webServerPort;
  private HttpProxyServer proxyServer;

  @BeforeEach
  void setUp() throws Exception {
    webServer = TestUtils.startWebServer(true, DEFAULT_JKS_KEYSTORE_PATH);

    webServerPort = TestUtils.findLocalHttpPort(webServer);
    if (webServerPort < 0) {
      throw new RuntimeException(
          "HTTP connector should already be open and listening, but port was " + webServerPort);
    }

    LOG.info("Started webserver on http:{}", webServerPort);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (proxyServer != null) {
        LOG.info("Stop proxy server {}", proxyServer.getListenAddress());
        proxyServer.abort();
      }
    } finally {
      if (webServer != null) {
        LOG.info("Stop webserver on http:{}, https:{}", webServerPort, webServerPort);
        webServer.stop();
      }
    }
  }

  /**
   * This test reproduces issue #71: When MITM is enabled and a CONNECT request is made to a non-SSL
   * server (like what happens with ws:// websockets), the proxy incorrectly tries to establish an
   * SSL connection to the server, which fails.
   *
   * <p>Scenario: 1. Client sends a CONNECT request to the proxy (e.g., for a websocket connection)
   * 2. The proxy, in MITM mode, should establish a plain TCP tunnel to the target server 3. But the
   * bug causes the proxy to add SSL to the upstream connection, which fails because the server
   * doesn't speak SSL
   *
   * <p>This is exactly what happens with browsers and websockets - the browser sends a CONNECT
   * request to the proxy, but the target websocket server doesn't use SSL.
   */
  @Test
  void testConnectRequestToNonSslServerInMitmMode() throws Exception {
    // Set up MITM proxy
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withManInTheMiddle(new TestMitmManager())
            .start();

    int proxyPort = proxyServer.getListenAddress().getPort();
    LOG.info("Started MITM proxy on port {}", proxyPort);

    // Send a CONNECT request to the non-SSL HTTP server
    // This simulates what browsers do when connecting to ws:// (websocket) servers
    try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
      socket.setSoTimeout(10000);

      // Send CONNECT request to plain HTTP server (not HTTPS)
      String connectRequest =
          "CONNECT 127.0.0.1:"
              + webServerPort
              + " HTTP/1.1\r\n"
              + "Host: 127.0.0.1:"
              + webServerPort
              + "\r\n"
              + "\r\n";

      socket.getOutputStream().write(connectRequest.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();

      // Read response
      byte[] buffer = new byte[4096];
      int read;
      StringBuilder response = new StringBuilder();
      try {
        while ((read = socket.getInputStream().read(buffer)) != -1) {
          response.append(new String(buffer, 0, read, StandardCharsets.US_ASCII));
          if (response.toString().contains("\r\n\r\n")) {
            break;
          }
        }
      } catch (IOException e) {
        LOG.error("IOException reading response", e);
        String exceptionMessage = e.getMessage();
        if (exceptionMessage != null
            && (exceptionMessage.toLowerCase().contains("ssl")
                || exceptionMessage.toLowerCase().contains("handshake")
                || exceptionMessage.toLowerCase().contains("not an ssl"))) {
          fail(
              "Issue #71 reproduced: Proxy incorrectly attempted SSL for non-SSL destination. "
                  + "Exception: "
                  + e.getMessage());
        }
        throw e;
      }

      LOG.info("CONNECT response: {}", response);

      String responseStr = response.toString();

      // Check if we got a Bad Gateway response - this happens when the SSL handshake fails
      if (responseStr.contains("502 Bad Gateway") || responseStr.contains("Bad Gateway")) {
        fail(
            "Issue #71 reproduced: Proxy incorrectly attempted SSL for non-SSL destination. "
                + "Got 502 Bad Gateway because the SSL handshake failed with a non-SSL server.");
      }

      // If we get here, the fix is in place
      assertThat(responseStr)
          .as("Should receive successful CONNECT response (200)")
          .contains("200");
    }
  }
}
