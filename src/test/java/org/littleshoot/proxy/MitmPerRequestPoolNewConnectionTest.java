package org.littleshoot.proxy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.littleshoot.proxy.extras.TestMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Regression test for #866: with per-request MITM pooling, a MITM'd request that finds no idle
 * connection to its {@code CONNECT} target in the pool must get a new connection over TLS, not
 * plain HTTP to the TLS port.
 */
@Timeout(30)
final class MitmPerRequestPoolNewConnectionTest {

  private final CountDownLatch slowRequestReceived = new CountDownLatch(1);
  private final CountDownLatch releaseSlowRequest = new CountDownLatch(1);

  private final Server webServer =
      TestUtils.startWebServer(true, "target/littleproxy_keystore.jks", new BlockingHandler());
  private final HttpHost httpsTarget =
      new HttpHost("127.0.0.1", TestUtils.findLocalHttpsPort(webServer), "https");

  private final Queue<Integer> serverConnectionPorts = new ConcurrentLinkedQueue<>();

  private final HttpProxyServer proxyServer =
      DefaultHttpProxyServer.bootstrap()
          .withPort(0)
          .withManInTheMiddle(new TestMitmManager())
          .withSharedServerConnectionPool(true)
          .withPoolSharedMitmConnections(true)
          .withPoolPerRequestInMitm(true)
          .plusActivityTracker(
              new ActivityTrackerAdapter() {
                @Override
                public void serverConnected(
                    FullFlowContext flowContext, InetSocketAddress serverAddress) {
                  serverConnectionPorts.add(serverAddress.getPort());
                }
              })
          .start();

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @AfterEach
  void tearDown() throws Exception {
    releaseSlowRequest.countDown();
    executor.shutdownNow();
    try {
      proxyServer.abort();
    } finally {
      webServer.stop();
    }
  }

  @Test
  void createsNewPooledConnectionOverTlsWhenNoneIsIdle() throws Exception {
    try (CloseableHttpClient clientA = newClient();
        CloseableHttpClient clientB = newClient()) {
      // Client B MITMs the target; its connection goes back to the pool after each request.
      assertThat(get(clientB, "/fast")).isEqualTo("Received 0 bytes\n");

      // Client A MITMs the same target and holds the only pooled connection with a slow request.
      CompletableFuture<String> slow =
          CompletableFuture.supplyAsync(() -> getUnchecked(clientA, "/slow"), executor);
      assertThat(slowRequestReceived.await(10, SECONDS)).isTrue();

      // Client B's next request on its MITM'd connection finds nothing idle in the pool.
      assertThat(get(clientB, "/fast"))
          .as("the new upstream connection must speak TLS to the CONNECT target")
          .isEqualTo("Received 0 bytes\n");

      releaseSlowRequest.countDown();
      assertThat(slow.get(10, SECONDS)).isEqualTo("Received 0 bytes\n");
    }

    assertThat(serverConnectionPorts)
        .as("the second request from client B must have needed a new server connection")
        .containsExactly(httpsTarget.getPort(), httpsTarget.getPort());
  }

  private CloseableHttpClient newClient() {
    return TestUtils.buildHttpClient(
        true, true, proxyServer.getListenAddress().getPort(), null, null);
  }

  private String get(CloseableHttpClient client, String path) throws IOException {
    HttpGet request = new HttpGet(path);
    request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
    HttpResponse response = client.execute(httpsTarget, request);
    String body = EntityUtils.toString(response.getEntity());
    assertThat(response.getStatusLine().getStatusCode()).as(body).isEqualTo(200);
    return body;
  }

  private String getUnchecked(CloseableHttpClient client, String path) {
    try {
      return get(client, path);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Answers every request, holding "/slow" until the test releases it. */
  private final class BlockingHandler extends AbstractHandler {
    @Override
    public void handle(
        String target,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException {
      if (request.getRequestURI().equals("/slow")) {
        slowRequestReceived.countDown();
        try {
          if (!releaseSlowRequest.await(20, SECONDS)) {
            throw new IOException("Slow request was never released");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
      }
      byte[] body = "Received 0 bytes\n".getBytes(UTF_8);
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentLength(body.length);
      baseRequest.setHandled(true);
      response.getOutputStream().write(body);
    }
  }
}
