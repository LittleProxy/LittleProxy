package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.littleshoot.proxy.extras.TestMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Regression test for #856: clients omit the default port from the {@code Host} header, so on port
 * 443 it doesn't match the {@code CONNECT} target. The test backend runs on an ephemeral port, so
 * the port is dropped from the header explicitly.
 */
@Timeout(30)
final class MitmConnectTargetTest {

  private static final String BACKEND_RESPONSE = "Received 0 bytes\n";

  enum Pooling {
    NONE(bootstrap -> bootstrap),
    SHARED_MITM_CONNECTIONS(
        bootstrap ->
            bootstrap.withSharedServerConnectionPool(true).withPoolSharedMitmConnections(true)),
    PER_REQUEST_IN_MITM(
        bootstrap ->
            bootstrap
                .withSharedServerConnectionPool(true)
                .withPoolSharedMitmConnections(true)
                .withPoolPerRequestInMitm(true));

    private final UnaryOperator<HttpProxyServerBootstrap> configuration;

    Pooling(UnaryOperator<HttpProxyServerBootstrap> configuration) {
      this.configuration = configuration;
    }
  }

  private final Server webServer =
      TestUtils.startWebServer(true, "target/littleproxy_keystore.jks");
  private final HttpHost httpTarget =
      new HttpHost("127.0.0.1", TestUtils.findLocalHttpPort(webServer));
  private final HttpHost httpsTarget =
      new HttpHost("127.0.0.1", TestUtils.findLocalHttpsPort(webServer), "https");

  private final AtomicInteger clientConnections = new AtomicInteger();
  private final Queue<Integer> serverConnectionPorts = new ConcurrentLinkedQueue<>();

  /** The method and server host and port of each request the proxy sent upstream. */
  private final Queue<String> requestsSentToServer = new ConcurrentLinkedQueue<>();

  @Nullable private HttpProxyServer proxyServer;

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (proxyServer != null) {
        proxyServer.abort();
      }
    } finally {
      webServer.stop();
    }
  }

  @ParameterizedTest
  @EnumSource(Pooling.class)
  void sendsRequestsToConnectTargetWhenHostHeaderOmitsPort(Pooling pooling) throws IOException {
    try (CloseableHttpClient client = start(pooling)) {
      for (int i = 0; i < 2; i++) {
        assertThat(executeWithoutPortInHostHeader(client)).as("request %d", i).isEqualTo(200);
      }
    }

    String connectTarget = httpsTarget.toHostString();
    assertThat(clientConnections.get())
        .as("both requests must go over the same MITM'd client connection")
        .isEqualTo(1);
    assertThat(serverConnectionPorts)
        .as("both requests must go over the server connection the CONNECT set up")
        .containsExactly(httpsTarget.getPort());
    assertThat(requestsSentToServer)
        .containsExactly("GET " + connectTarget, "GET " + connectTarget);
  }

  @ParameterizedTest
  @EnumSource(Pooling.class)
  void doesNotRouteNonMitmRequestsToConnectTarget(Pooling pooling) throws IOException {
    try (CloseableHttpClient client = start(pooling)) {
      assertThat(executeWithoutPortInHostHeader(client)).isEqualTo(200);

      HttpGet request = new HttpGet("/");
      request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
      HttpResponse response = client.execute(httpTarget, request);

      assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
      assertThat(EntityUtils.toString(response.getEntity())).isEqualTo(BACKEND_RESPONSE);
    }

    assertThat(requestsSentToServer)
        .containsExactly("GET " + httpsTarget.toHostString(), "GET " + httpTarget.toHostString());
  }

  private int executeWithoutPortInHostHeader(CloseableHttpClient client) throws IOException {
    HttpGet request = new HttpGet("/");
    request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
    request.setHeader(HttpHeaders.HOST, httpsTarget.getHostName());

    HttpResponse response = client.execute(httpsTarget, request);

    assertThat(EntityUtils.toString(response.getEntity()))
        .as("the response must come from the CONNECT target")
        .isEqualTo(BACKEND_RESPONSE);
    return response.getStatusLine().getStatusCode();
  }

  private CloseableHttpClient start(Pooling pooling) {
    HttpProxyServer server =
        pooling
            .configuration
            .apply(DefaultHttpProxyServer.bootstrap())
            .withPort(0)
            .withManInTheMiddle(new TestMitmManager())
            .plusActivityTracker(
                new ActivityTrackerAdapter() {
                  @Override
                  public void clientConnected(FlowContext flowContext) {
                    clientConnections.incrementAndGet();
                  }

                  @Override
                  public void serverConnected(
                      FullFlowContext flowContext, InetSocketAddress serverAddress) {
                    serverConnectionPorts.add(serverAddress.getPort());
                  }

                  @Override
                  public void requestSentToServer(
                      FullFlowContext flowContext, HttpRequest httpRequest) {
                    requestsSentToServer.add(
                        httpRequest.method() + " " + flowContext.getServerHostAndPort());
                  }
                })
            .start();
    proxyServer = server;
    return TestUtils.buildHttpClient(true, true, server.getListenAddress().getPort(), null, null);
  }
}
