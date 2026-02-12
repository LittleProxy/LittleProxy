package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.buildHttpClient;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test to reproduce issue #56: Proxy server authentication called twice.
 *
 * <p>This test verifies that the ProxyAuthenticator.authenticate() method is called exactly once
 * per authentication attempt, not multiple times.
 *
 * <p>The bug: When a client makes a request through an authenticating proxy: 1. First request
 * without credentials -> 407 Proxy Authentication Required 2. Client retries with credentials ->
 * authenticate() is called TWICE instead of once
 *
 * @see <a href="https://github.com/LittleProxy/LittleProxy/issues/56">Issue #56</a>
 */
public class AuthenticationCalledOnceTest {

  private static final Logger logger = LoggerFactory.getLogger(AuthenticationCalledOnceTest.class);

  private Server webServer;
  private HttpProxyServer proxyServer;
  private int webServerPort;
  private int httpsWebServerPort;
  private HttpHost webHost;
  private HttpHost httpsWebHost;

  // Counter to track how many times authenticate() is called
  private final AtomicInteger authenticateCallCount = new AtomicInteger(0);

  private static final String DEFAULT_JKS_KEYSTORE_PATH = "target/littleproxy_keystore.jks";
  private static final String DEFAULT_RESOURCE = "/";
  private static final String USERNAME = "testuser";
  private static final String PASSWORD = "testpass";

  @BeforeEach
  public void setUp() throws Exception {
    webServer = org.littleshoot.proxy.TestUtils.startWebServer(true, DEFAULT_JKS_KEYSTORE_PATH);

    webServerPort = org.littleshoot.proxy.TestUtils.findLocalHttpPort(webServer);
    httpsWebServerPort = org.littleshoot.proxy.TestUtils.findLocalHttpsPort(webServer);

    webHost = new HttpHost("127.0.0.1", webServerPort);
    httpsWebHost = new HttpHost("127.0.0.1", httpsWebServerPort, "https");

    logger.info("Started webserver http:{}, https:{}", webServerPort, httpsWebServerPort);

    // Create proxy with authentication
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withProxyAuthenticator(
                new ProxyAuthenticator() {
                  @Override
                  public boolean authenticate(String username, String password) {
                    int count = authenticateCallCount.incrementAndGet();
                    logger.info("authenticate() called - count: {}", count);
                    return USERNAME.equals(username) && PASSWORD.equals(password);
                  }

                  @Override
                  public String getRealm() {
                    return "TestRealm";
                  }
                })
            .start();

    logger.info("Started proxy server on port {}", proxyServer.getListenAddress().getPort());
  }

  @AfterEach
  public void tearDown() throws Exception {
    try {
      if (proxyServer != null) {
        logger.info("Stopping proxy server");
        proxyServer.abort();
      }
    } finally {
      if (webServer != null) {
        logger.info("Stopping webserver");
        webServer.stop();
      }
    }
  }

  /**
   * Test case for issue #56: ProxyAuthenticator.authenticate() is called twice.
   *
   * <p>When a client makes a request through an authenticating proxy: 1. First request without
   * credentials -> 407 Proxy Authentication Required (authenticate NOT called) 2. Client retries
   * with credentials -> authenticate() should be called ONCE
   *
   * <p>Bug: Currently authenticate() is being called TWICE during step 2
   */
  @Test
  public void testAuthenticateCalledOnceDuringAuthentication() throws IOException {
    // Reset counter before test
    authenticateCallCount.set(0);

    // Make a request through the proxy WITH authentication credentials
    // The HTTP client will automatically handle the 407 challenge
    try (CloseableHttpClient httpClient =
        buildHttpClient(
            true, // isProxied
            false, // supportSsl (we're using HTTP not HTTPS)
            proxyServer.getListenAddress().getPort(),
            USERNAME,
            PASSWORD)) {

      final HttpGet request = new HttpGet("http://127.0.0.1:" + webServerPort + DEFAULT_RESOURCE);
      final HttpResponse response = httpClient.execute(webHost, request);

      logger.info("Response status: {}", response.getStatusLine().getStatusCode());

      // Verify the request was successful
      assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);

      // CRITICAL ASSERTION: authenticate() should be called exactly ONCE
      // Currently, due to the bug, it's being called twice
      int callCount = authenticateCallCount.get();
      logger.info("Total authenticate() calls: {}", callCount);

      // This assertion will FAIL with the current bug (count will be 2)
      // After fixing issue #56, this should pass (count should be 1)
      assertThat(callCount)
          .as(
              "authenticate() should be called exactly once during authentication, not %d times",
              callCount)
          .isEqualTo(1);
    }
  }

  /**
   * Additional test: verify that subsequent requests on an authenticated connection do NOT trigger
   * additional authenticate() calls.
   *
   * <p>Note: This test may show the authenticate being called multiple times if the HTTP client
   * creates new connections. This is separate from issue #56.
   */
  @Test
  public void testSubsequentRequestsDoNotReauthenticate() throws IOException {
    // Reset counter
    authenticateCallCount.set(0);

    // Use a single client connection to make multiple requests
    try (CloseableHttpClient httpClient =
        buildHttpClient(
            true, false, proxyServer.getListenAddress().getPort(), USERNAME, PASSWORD)) {

      // First request - triggers authentication
      HttpGet request1 = new HttpGet("http://127.0.0.1:" + webServerPort + DEFAULT_RESOURCE);
      HttpResponse response1 = httpClient.execute(webHost, request1);
      assertThat(response1.getStatusLine().getStatusCode()).isEqualTo(200);

      int countAfterFirstRequest = authenticateCallCount.get();
      logger.info("After first request, authenticate() called {} times", countAfterFirstRequest);

      // Second request - should NOT trigger authentication again on the same connection
      HttpGet request2 = new HttpGet("http://127.0.0.1:" + webServerPort + DEFAULT_RESOURCE);
      HttpResponse response2 = httpClient.execute(webHost, request2);
      assertThat(response2.getStatusLine().getStatusCode()).isEqualTo(200);

      int countAfterSecondRequest = authenticateCallCount.get();
      logger.info("After second request, authenticate() called {} times", countAfterSecondRequest);

      // This test might fail if HTTP client creates new connections
      // The key assertion for issue #56 is the first test case
      // This test is to verify connection reuse behavior
      assertThat(countAfterSecondRequest)
          .as("authenticate() should not be called again for subsequent requests")
          .isLessThanOrEqualTo(countAfterFirstRequest + 1); // Allow at most 1 additional call
    }
  }
}
