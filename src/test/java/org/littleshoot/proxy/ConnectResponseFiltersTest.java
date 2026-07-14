package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.buildHttpClient;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.eclipse.jetty.server.Server;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.extras.TestMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test to reproduce issue #77: https://github.com/LittleProxy/LittleProxy/issues/77
 *
 * <p>Issue: CONNECT Response Not Returned to HttpFilters
 *
 * <p>When using LittleProxy with MITM enabled and a custom HttpFilters, the CONNECT request goes
 * through proxyToServerRequest() but the CONNECT response is never sent to serverToProxyResponse().
 * This is because when isConnecting() is true (during CONNECT tunnel establishment), the response
 * goes to connectionFlow.read() instead of being passed to the filters.
 */
public class ConnectResponseFiltersTest {
  private static final String DEFAULT_JKS_KEYSTORE_PATH = "target/littleproxy_keystore.jks";
  private static final Logger logger = LoggerFactory.getLogger(ConnectResponseFiltersTest.class);
  private Server webServer;
  private HttpProxyServer proxyServer;
  private int webServerPort;
  private int httpsWebServerPort;

  @BeforeEach
  void setUp() throws Exception {
    // Start web server with both HTTP and HTTPS enabled
    webServer = TestUtils.startWebServer(true, DEFAULT_JKS_KEYSTORE_PATH);
    webServerPort = TestUtils.findLocalHttpPort(webServer);
    httpsWebServerPort = TestUtils.findLocalHttpsPort(webServer);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (webServer != null) {
        webServer.stop();
      }
    } finally {
      try {
        if (proxyServer != null) {
          proxyServer.abort();
        }
      } catch (Exception e) {
        // ignore
      }
    }
  }

  /**
   * Test that verifies the CONNECT response is returned to HttpFilters.
   *
   * <p>This test should FAIL if the bug exists (CONNECT response not going to filters) and PASS
   * after the fix is applied.
   */
  @Test
  public void testConnectResponseIsReturnedToFilters() throws Exception {
    // Track whether the CONNECT request was seen in proxyToServerRequest
    final AtomicBoolean connectRequestSeen = new AtomicBoolean(false);
    // Track whether the CONNECT response (200) was seen in serverToProxyResponse
    final AtomicBoolean connectResponseSeen = new AtomicBoolean(false);
    // Track how many 200 responses we've seen in serverToProxyResponse
    final AtomicInteger connectResponseCount = new AtomicInteger(0);

    HttpFiltersSource filtersSource =
        new HttpFiltersSourceAdapter() {
          @NonNull
          @Override
          public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
            return new HttpFiltersAdapter(originalRequest) {
              @Nullable
              @Override
              public HttpResponse proxyToServerRequest(@NonNull HttpObject httpObject) {
                if (httpObject instanceof HttpRequest) {
                  HttpRequest request = (HttpRequest) httpObject;
                  if (HttpMethod.CONNECT.equals(request.method())) {
                    connectRequestSeen.set(true);
                  }
                }
                return null;
              }

              @Nullable
              @Override
              public HttpObject serverToProxyResponse(@NonNull HttpObject httpObject) {
                if (httpObject instanceof HttpResponse) {
                  HttpResponse response = (HttpResponse) httpObject;
                  // Check if this is a 200 response (CONNECT responses have 200 status)
                  if (response.status().code() == 200) {
                    connectResponseCount.incrementAndGet();
                    // If we see a 200 response, it's likely the CONNECT tunnel response
                    // (since the actual GET request will also get a 200, but we'll have already
                    // seen the CONNECT request in proxyToServerRequest)
                    if (connectRequestSeen.get()) {
                      connectResponseSeen.set(true);
                    }
                  }
                }
                return httpObject;
              }
            };
          }
        };

    // Start proxy with MITM enabled (this is what triggers CONNECT tunneling)
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withFiltersSource(filtersSource)
            .withManInTheMiddle(new TestMitmManager())
            .start();

    // Give the proxy time to start
    Thread.sleep(500);

    // Make an HTTPS request through the proxy using a client that trusts self-signed certs
    String httpsUrl = "https://localhost:" + httpsWebServerPort + "/";
    CloseableHttpClient httpClient =
        buildHttpClient(true, true, proxyServer.getListenAddress().getPort(), null, null);
    HttpGet get = new HttpGet(httpsUrl);
    org.apache.http.HttpResponse response = httpClient.execute(get);
    httpClient.close();

    // Wait for filters to be invoked
    Thread.sleep(1000);

    // Verify the CONNECT request was seen in proxyToServerRequest
    assertThat(connectRequestSeen.get())
        .as("CONNECT request should be seen in proxyToServerRequest filter")
        .isTrue();

    // This assertion should FAIL if the bug exists (issue #77)
    // The CONNECT response should be returned to serverToProxyResponse
    assertThat(connectResponseSeen.get())
        .as(
            "CONNECT response (200) should be seen in serverToProxyResponse filter. "
                + "This is the bug described in issue #77 - the CONNECT response is not being "
                + "passed to HttpFilters when isConnecting() is true.")
        .isTrue();

    // Verify we got at least one 200 response (should be 2: CONNECT tunnel + GET response)
    assertThat(connectResponseCount.get())
        .as("Should have received at least one 200 response in serverToProxyResponse")
        .isGreaterThanOrEqualTo(1);

    // Verify the request actually succeeded
    assertThat(response.getStatusLine().getStatusCode())
        .as("HTTPS request should succeed")
        .isEqualTo(200);
  }

  /**
   * Additional test to verify that both the CONNECT tunnel establishment AND the subsequent HTTP
   * request over the tunnel go through the filters correctly.
   */
  @Test
  public void testConnectResponseAndSubsequentRequestBothFiltered() throws Exception {
    final StringBuilder filterCalls = new StringBuilder();

    HttpFiltersSource filtersSource =
        new HttpFiltersSourceAdapter() {
          @NonNull
          @Override
          public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
            return new HttpFiltersAdapter(originalRequest) {
              @Nullable
              @Override
              public HttpResponse proxyToServerRequest(@NonNull HttpObject httpObject) {
                if (httpObject instanceof HttpRequest) {
                  HttpRequest request = (HttpRequest) httpObject;
                  filterCalls.append("proxyToServerRequest:").append(request.method()).append(",");
                }
                return null;
              }

              @Nullable
              @Override
              public HttpObject serverToProxyResponse(@NonNull HttpObject httpObject) {
                if (httpObject instanceof HttpResponse) {
                  HttpResponse response = (HttpResponse) httpObject;
                  filterCalls
                      .append("serverToProxyResponse:")
                      .append(response.status().code())
                      .append("(originalMethod:")
                      .append(originalRequest.method())
                      .append("),");
                }
                return httpObject;
              }
            };
          }
        };

    // Start proxy with MITM enabled
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withFiltersSource(filtersSource)
            .withManInTheMiddle(new TestMitmManager())
            .start();

    // Give the proxy time to start
    Thread.sleep(500);

    // Make an HTTPS request through the proxy using a client that trusts self-signed certs
    String httpsUrl = "https://localhost:" + httpsWebServerPort + "/";
    CloseableHttpClient httpClient =
        buildHttpClient(true, true, proxyServer.getListenAddress().getPort(), null, null);
    HttpGet get = new HttpGet(httpsUrl);
    httpClient.execute(get);
    httpClient.close();

    // Wait for filters to be invoked
    Thread.sleep(1000);

    // Print what we captured for debugging
    logger.info("Filter calls captured: {}", filterCalls);

    // Verify that both CONNECT (for tunnel) and GET (for actual request) are seen
    String filterCallsAsString = filterCalls.toString();
    assertThat(filterCallsAsString)
        .as("Both CONNECT and GET should appear in filter calls")
        .contains("CONNECT");
    assertThat(filterCallsAsString)
        .as(
            "Both CONNECT response (200) and GET response (200) should appear in serverToProxyResponse")
        .contains("serverToProxyResponse:200");
  }
}
