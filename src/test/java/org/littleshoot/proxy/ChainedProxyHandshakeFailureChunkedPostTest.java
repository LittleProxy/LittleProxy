package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * End-to-end regression test for the retry path fixed in #827: a chained proxy accepts the TCP
 * connection and then fails the TLS handshake. The downstream proxy must fall back to a direct
 * connection and deliver the full chunked POST body, not a truncated one.
 *
 * <p>The unit test {@code
 * ProxyToServerConnectionWriteBlockingTest.flowFailureKeepsQueuedWritesForRetry} checks the same
 * ordering in isolation; this test exercises the real proxy, the real Netty pipeline and a real
 * client.
 */
final class ChainedProxyHandshakeFailureChunkedPostTest {

  private static final String BODY = "adsf";
  private static final String EXPECTED = "Received " + BODY.length() + " bytes\n";

  private final AtomicInteger handshakeAttempts = new AtomicInteger();

  private Server webServer;
  private ServerSocket failingChainedProxy;
  private HttpProxyServer proxyServer;
  private int webPort;

  @BeforeEach
  void setUp() throws Exception {
    webServer = TestUtils.startWebServer(false, "target/littleproxy_keystore.jks");
    webPort = TestUtils.findLocalHttpPort(webServer);

    // A chained proxy that accepts the TCP connection and immediately closes it, so the TLS
    // handshake the downstream proxy attempts against it fails.
    failingChainedProxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread acceptor =
        new Thread(
            () -> {
              while (!failingChainedProxy.isClosed()) {
                try {
                  Socket socket = failingChainedProxy.accept();
                  handshakeAttempts.incrementAndGet();
                  socket.close();
                } catch (IOException e) {
                  return;
                }
              }
            },
            "failing-chained-proxy");
    acceptor.setDaemon(true);
    acceptor.start();

    InetSocketAddress chainedAddress =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), failingChainedProxy.getLocalPort());
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withChainProxyManager(
                (httpRequest, chainedProxies, clientDetails) -> {
                  chainedProxies.add(
                      new ChainedProxyAdapter() {
                        @Override
                        public InetSocketAddress getChainedProxyAddress() {
                          return chainedAddress;
                        }

                        @Override
                        public boolean requiresEncryption() {
                          return true;
                        }

                        @Override
                        public SSLEngine newSslEngine() {
                          return defaultSslEngine();
                        }
                      });
                  chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
                })
            .start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (proxyServer != null) {
      proxyServer.abort();
    }
    if (failingChainedProxy != null) {
      failingChainedProxy.close();
    }
    if (webServer != null) {
      webServer.stop();
    }
  }

  @Test
  @DisplayName("a chunked POST survives a chained-proxy handshake failure and arrives in full")
  void chunkedPostArrivesInFullAfterHandshakeFailure() throws Exception {
    try (CloseableHttpClient client =
        TestUtils.buildHttpClient(
            true, false, proxyServer.getListenAddress().getPort(), null, null)) {
      HttpPost request = new HttpPost("/");
      request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
      StringEntity entity = new StringEntity(BODY, "UTF-8");
      entity.setChunked(true);
      request.setEntity(entity);

      HttpResponse response = client.execute(new HttpHost("127.0.0.1", webPort), request);
      String responseBody = EntityUtils.toString(response.getEntity());

      assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
      assertThat(responseBody)
          .as("the fallback to the direct connection must deliver the whole chunked body")
          .isEqualTo(EXPECTED);
    }
    assertThat(handshakeAttempts.get())
        .as("the failing chained proxy should have been tried before the fallback")
        .isPositive();
  }

  private static SSLEngine defaultSslEngine() {
    try {
      return SSLContext.getDefault().createSSLEngine();
    } catch (Exception e) {
      throw new IllegalStateException("Unable to create a default SSLEngine", e);
    }
  }
}
