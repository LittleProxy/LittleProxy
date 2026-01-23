package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.buildHttpClient;

import io.netty.handler.codec.http.HttpRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base for tests that test the proxy. This base class encapsulates all the testing infrastructure.
 */
public abstract class AbstractProxyTest {
  protected static final String DEFAULT_RESOURCE = "/";
  private static final String DEFAULT_JKS_KEYSTORE_PATH = "target/littleproxy_keystore.jks";

  protected int webServerPort = -1;
  protected int httpsWebServerPort = -1;

  protected HttpHost webHost;
  protected HttpHost httpsWebHost;

  /** The server used by the tests. */
  protected HttpProxyServer proxyServer;

  /** Holds the most recent response after executing a test method. */
  protected String lastResponse;

  /** The web server that provides the back-end. */
  private Server webServer;

  private final AtomicInteger bytesReceivedFromClient = new AtomicInteger(0);
  private final AtomicInteger requestsReceivedFromClient = new AtomicInteger(0);
  private final AtomicInteger bytesSentToServer = new AtomicInteger(0);
  private final AtomicInteger requestsSentToServer = new AtomicInteger(0);
  private final AtomicInteger bytesReceivedFromServer = new AtomicInteger(0);
  private final AtomicInteger responsesReceivedFromServer = new AtomicInteger(0);
  private final AtomicInteger bytesSentToClient = new AtomicInteger(0);
  private final AtomicInteger responsesSentToClient = new AtomicInteger(0);
  private final AtomicInteger clientConnects = new AtomicInteger(0);
  private final AtomicInteger clientSSLHandshakeSuccesses = new AtomicInteger(0);
  private final AtomicInteger clientDisconnects = new AtomicInteger(0);

  protected Logger logger() {
    return LoggerFactory.getLogger(getClass());
  }

  @BeforeEach
  final void runSetUp() throws Exception {
    webServer = TestUtils.startWebServer(true, DEFAULT_JKS_KEYSTORE_PATH);

    // find out what ports the HTTP and HTTPS connectors were bound to
    httpsWebServerPort = TestUtils.findLocalHttpsPort(webServer);
    if (httpsWebServerPort < 0) {
      throw new RuntimeException(
          "HTTPS connector should already be open and listening, but port was " + webServerPort);
    }

    webServerPort = TestUtils.findLocalHttpPort(webServer);
    if (webServerPort < 0) {
      throw new RuntimeException(
          "HTTP connector should already be open and listening, but port was " + webServerPort);
    }

    webHost = new HttpHost("127.0.0.1", webServerPort);
    httpsWebHost = new HttpHost("127.0.0.1", httpsWebServerPort, "https");
    logger().info("Started webserver http:{}, https:{}", webServerPort, httpsWebServerPort);

    setUp();
    logger().info("Started proxy server {}", proxyServer.getListenAddress());
  }

  protected abstract void setUp() throws Exception;

  @AfterEach
  final void runTearDown() throws Exception {
    try {
      tearDown();
    } finally {
      try {
        if (proxyServer != null) {
          logger().info("Stop proxy server {}", proxyServer.getListenAddress());
          proxyServer.abort();
        }
      } finally {
        if (webServer != null) {
          logger().info("Stop webserver http:{}, https:{}", webServerPort, httpsWebServerPort);
          webServer.stop();
        }
      }
    }
  }

  protected void tearDown() throws Exception {}

  /** Override this to specify a username to use when authenticating with proxy. */
  protected String getUsername() {
    return null;
  }

  /** Override this to specify a password to use when authenticating with proxy. */
  protected String getPassword() {
    return null;
  }

  protected void assertReceivedBadGateway(ResponseInfo response) {
    assertThat(response.getStatusCode()).as("Received: %s", response).isEqualTo(502);
  }

  protected ResponseInfo httpPostWithApacheClient(
      HttpHost host, String resourceUrl, boolean isProxied) {
    final boolean supportSsl = true;
    String username = getUsername();
    String password = getPassword();
    try (CloseableHttpClient httpClient =
        buildHttpClient(
            isProxied, supportSsl, proxyServer.getListenAddress().getPort(), username, password)) {
      final HttpPost request = new HttpPost(resourceUrl);
      request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);

      final StringEntity entity = new StringEntity("adsf", "UTF-8");
      entity.setChunked(true);
      request.setEntity(entity);

      final HttpResponse response = httpClient.execute(host, request);
      final HttpEntity resEntity = response.getEntity();
      return new ResponseInfo(
          response.getStatusLine().getStatusCode(), EntityUtils.toString(resEntity));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected ResponseInfo httpGetWithApacheClient(
      HttpHost host, String resourceUrl, boolean isProxied, boolean callHeadFirst) {
    final boolean supportSsl = true;
    String username = getUsername();
    String password = getPassword();
    try (CloseableHttpClient httpClient =
        buildHttpClient(
            isProxied, supportSsl, proxyServer.getListenAddress().getPort(), username, password)) {
      Integer contentLength = null;
      if (callHeadFirst) {
        HttpHead request = new HttpHead(resourceUrl);
        request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
        HttpResponse response = httpClient.execute(host, request);
        contentLength = Integer.valueOf(response.getFirstHeader("Content-Length").getValue());
      }

      HttpGet request = new HttpGet(resourceUrl);
      request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);

      HttpResponse response = httpClient.execute(host, request);
      HttpEntity resEntity = response.getEntity();

      if (contentLength != null) {
        assertThat(Integer.valueOf(response.getFirstHeader("Content-Length").getValue()))
            .as("Content-Length from GET should match that from HEAD")
            .isEqualTo(contentLength);
      }
      return new ResponseInfo(
          response.getStatusLine().getStatusCode(), EntityUtils.toString(resEntity));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected String compareProxiedAndUnproxiedPOST(HttpHost host, String resourceUrl) {
    ResponseInfo proxiedResponse = httpPostWithApacheClient(host, resourceUrl, true);
    if (expectBadGatewayForEverything()) {
      assertReceivedBadGateway(proxiedResponse);
    } else {
      ResponseInfo unproxiedResponse = httpPostWithApacheClient(host, resourceUrl, false);
      assertThat(proxiedResponse).isEqualTo(unproxiedResponse);
      checkStatistics(host);
    }
    return proxiedResponse.getBody();
  }

  protected String compareProxiedAndUnproxiedGET(HttpHost host, String resourceUrl) {
    ResponseInfo proxiedResponse = httpGetWithApacheClient(host, resourceUrl, true, false);
    if (expectBadGatewayForEverything()) {
      assertReceivedBadGateway(proxiedResponse);
    } else {
      ResponseInfo unproxiedResponse = httpGetWithApacheClient(host, resourceUrl, false, false);
      assertThat(proxiedResponse).isEqualTo(unproxiedResponse);
      checkStatistics(host);
    }
    return proxiedResponse.getBody();
  }

  private void checkStatistics(HttpHost host) {
    boolean isHTTPS = "HTTPS".equalsIgnoreCase(host.getSchemeName());
    int numberOfExpectedClientInteractions = 1;
    int numberOfExpectedServerInteractions = 1;
    if (isAuthenticating()) {
      numberOfExpectedClientInteractions += 1;
    }
    if (isHTTPS && isMITM()) {
      numberOfExpectedClientInteractions += 1;
      numberOfExpectedServerInteractions += 1;
    }
    if (isHTTPS && !isChained()) {
      numberOfExpectedServerInteractions -= 1;
    }
    assertThat(bytesReceivedFromClient.get()).isGreaterThan(0);
    assertThat(requestsReceivedFromClient.get()).isEqualTo(numberOfExpectedClientInteractions);
    assertThat(bytesSentToServer.get()).isGreaterThan(0);
    assertThat(requestsSentToServer.get()).isEqualTo(numberOfExpectedServerInteractions);
    assertThat(bytesReceivedFromServer.get()).isGreaterThan(0);
    assertThat(responsesReceivedFromServer.get()).isEqualTo(numberOfExpectedServerInteractions);
    assertThat(bytesSentToClient.get()).isGreaterThan(0);
    assertThat(responsesSentToClient.get()).isEqualTo(numberOfExpectedClientInteractions);
  }

  /** Override this to indicate that the proxy is chained. */
  protected boolean isChained() {
    return false;
  }

  /** Override this to indicate that the test does use authentication. */
  protected boolean isAuthenticating() {
    return false;
  }

  protected boolean isMITM() {
    return false;
  }

  protected boolean expectBadGatewayForEverything() {
    return false;
  }

  protected HttpProxyServerBootstrap bootstrapProxy() {
    return DefaultHttpProxyServer.bootstrap()
        .plusActivityTracker(
            new ActivityTracker() {
              @Override
              public void bytesReceivedFromClient(FlowContext flowContext, int numberOfBytes) {
                bytesReceivedFromClient.addAndGet(numberOfBytes);
              }

              @Override
              public void requestReceivedFromClient(
                  FlowContext flowContext, HttpRequest httpRequest) {
                requestsReceivedFromClient.incrementAndGet();
              }

              @Override
              public void bytesSentToServer(FullFlowContext flowContext, int numberOfBytes) {
                bytesSentToServer.addAndGet(numberOfBytes);
              }

              @Override
              public void requestSentToServer(
                  FullFlowContext flowContext, HttpRequest httpRequest) {
                requestsSentToServer.incrementAndGet();
              }

              @Override
              public void bytesReceivedFromServer(FullFlowContext flowContext, int numberOfBytes) {
                bytesReceivedFromServer.addAndGet(numberOfBytes);
              }

              @Override
              public void responseReceivedFromServer(
                  FullFlowContext flowContext,
                  io.netty.handler.codec.http.HttpResponse httpResponse) {
                responsesReceivedFromServer.incrementAndGet();
              }

              @Override
              public void bytesSentToClient(FlowContext flowContext, int numberOfBytes) {
                bytesSentToClient.addAndGet(numberOfBytes);
              }

              @Override
              public void responseSentToClient(
                  FlowContext flowContext, io.netty.handler.codec.http.HttpResponse httpResponse) {
                responsesSentToClient.incrementAndGet();
              }

              @Override
              public void clientConnected(InetSocketAddress clientAddress) {
                clientConnects.incrementAndGet();
              }

              @Override
              public void clientSSLHandshakeSucceeded(
                  InetSocketAddress clientAddress, SSLSession sslSession) {
                clientSSLHandshakeSuccesses.incrementAndGet();
              }

              @Override
              public void clientDisconnected(
                  InetSocketAddress clientAddress, SSLSession sslSession) {
                clientDisconnects.incrementAndGet();
              }
            });
  }
}
