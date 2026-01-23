package org.littleshoot.proxy;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.createProxiedHttpClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/** Tests the proxy's handling and manipulation of headers. */
public final class ProxyHeadersTest {
  private HttpProxyServer proxyServer;

  private WireMockServer mockServer;
  private int mockServerPort;

  @BeforeEach
  void setUp() {
    mockServer = new WireMockServer(options().dynamicPort());
    mockServer.start();
    mockServerPort = mockServer.port();
  }

  @AfterEach
  void tearDown() {
    try {
      if (proxyServer != null) {
        proxyServer.abort();
      }
    } finally {
      if (mockServer != null) {
        mockServer.stop();
      }
    }
  }

  @Test
  public void testProxyRemovesConnectionHeadersFromServer() throws Exception {
    // the proxy should remove all Connection headers, since all values in the
    // Connection header are hop-by-hop headers.
    mockServer.stubFor(
        get(urlEqualTo("/connectionheaders"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("success")
                    .withHeader("Connection", "Dummy-Header")
                    .withHeader("Dummy-Header", "dummy-value")));

    proxyServer = DefaultHttpProxyServer.bootstrap().withPort(0).start();

    try (CloseableHttpClient httpClient =
        createProxiedHttpClient(proxyServer.getListenAddress().getPort())) {
      HttpResponse response =
          httpClient.execute(
              new HttpGet("http://localhost:" + mockServerPort + "/connectionheaders"));
      EntityUtils.consume(response.getEntity());

      Header[] dummyHeaders = response.getHeaders("Dummy-Header");
      assertThat(dummyHeaders)
          .as("Expected proxy to remove the Dummy-Header specified in the Connection header")
          .isEmpty();
    }
  }

  @Test
  public void testProxyRemovesHopByHopHeadersFromClient() throws Exception {
    mockServer.stubFor(
        get(urlEqualTo("/connectionheaders"))
            .willReturn(aResponse().withStatus(200).withBody("success")));

    proxyServer = DefaultHttpProxyServer.bootstrap().withPort(0).start();

    try (CloseableHttpClient httpClient =
        createProxiedHttpClient(proxyServer.getListenAddress().getPort())) {
      HttpGet clientRequest =
          new HttpGet("http://localhost:" + mockServerPort + "/connectionheaders");
      clientRequest.addHeader("Proxy-Authenticate", "");
      clientRequest.addHeader("Proxy-Authorization", "");
      HttpResponse response = httpClient.execute(clientRequest);
      EntityUtils.consume(response.getEntity());
      assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
    }

    mockServer.verify(
        getRequestedFor(urlEqualTo("/connectionheaders"))
            .withoutHeader("Proxy-Authenticate")
            .withoutHeader("Proxy-Authorization"));
  }
}
