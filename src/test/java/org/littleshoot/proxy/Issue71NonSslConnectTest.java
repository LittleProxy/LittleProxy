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
public class Issue71NonSslConnectTest {
  private static final Logger LOG = LoggerFactory.getLogger(Issue71NonSslConnectTest.class);

  private static final String DEFAULT_JKS_KEYSTORE_PATH = "target/littleproxy_keystore.jks";

  private Server webServer;
  private int webServerPort;
  private HttpProxyServer proxyServer;

  @BeforeEach
  public void setUp() throws Exception {
    webServer = TestUtils.startWebServer(true, DEFAULT_JKS_KEYSTORE_PATH);

    webServerPort = TestUtils.findLocalHttpPort(webServer);
    if (webServerPort < 0) {
      throw new RuntimeException(
          "HTTP connector should already be open and listening, but port was " + webServerPort);
    }

    LOG.info("Started webserver on http:{}", webServerPort);
  }

  @AfterEach
  public void tearDown() throws Exception {
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
   * <p>The test sends an explicit CONNECT request to a plain HTTP server (not HTTPS) while the
   * proxy is in MITM mode. In the buggy implementation, the proxy always adds SSL to the server
   * connection when MITM is enabled, regardless of whether the target server actually supports SSL.
   *
   * <p>Once the issue is fixed, this test should pass - the proxy should detect that the target
   * server doesn't require SSL based on the original request URI and NOT add SSL to the server
   * connection.
   */
  @Test
  public void testConnectToNonSslServerInMitmMode() throws Exception {
    // Set up MITM proxy
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withManInTheMiddle(new TestMitmManager())
            .start();

    int proxyPort = proxyServer.getListenAddress().getPort();
    LOG.info("Started MITM proxy on port {}", proxyPort);

    // Send a CONNECT request to the non-SSL HTTP server
    // This simulates what browsers do when connecting to ws:// (websocket) servers -
    // they send a CONNECT request, but the target server doesn't speak SSL
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
          // Check if we've got the complete response
          if (response.toString().contains("\r\n\r\n")) {
            break;
          }
        }
      } catch (IOException e) {
        LOG.error("IOException reading response", e);
        // Check if it's an SSL-related exception
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
      // because the proxy tried to use SSL with a non-SSL server
      if (responseStr.contains("502 Bad Gateway") || responseStr.contains("Bad Gateway")) {
        // This is the bug! The proxy tried to use SSL but the server doesn't speak SSL
        // The error message from Netty would be in the server logs:
        // "NotSslRecordException: not an SSL/TLS record"
        fail(
            "Issue #71 reproduced: Proxy incorrectly attempted SSL for non-SSL destination. "
                + "Got 502 Bad Gateway because the SSL handshake failed with a non-SSL server. "
                + "Response: "
                + responseStr);
      }

      // If we get here, the fix is in place - the proxy should successfully tunnel
      // to the non-SSL server without trying to use SSL
      assertThat(responseStr)
          .as("Should receive successful CONNECT response (200)")
          .contains("200");
    }
  }
}
