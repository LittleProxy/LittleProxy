package org.littleshoot.proxy;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableString;

import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.createProxiedHttpClient;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests the proxy's handling and manipulation of headers.
 */
public final class ProxyHeadersTest {
    private HttpProxyServer proxyServer;

    private ClientAndServer mockServer;
    private int mockServerPort;

    @BeforeEach
    void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
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
        // the proxy should remove all Connection headers, since all values in the Connection header are hop-by-hop headers.
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/connectionheaders"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success")
                        .withHeader("Connection", "Dummy-Header")
                        .withHeader("Dummy-Header", "dummy-value")
                        .withConnectionOptions(new ConnectionOptions()
                                .withSuppressConnectionHeader(true))
                );

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        try (CloseableHttpClient httpClient = createProxiedHttpClient(proxyServer.getListenAddress().getPort())) {
            HttpResponse response = httpClient.execute(new HttpGet("http://localhost:" + mockServerPort + "/connectionheaders"));
            EntityUtils.consume(response.getEntity());

            Header[] dummyHeaders = response.getHeaders("Dummy-Header");
            assertThat(dummyHeaders)
              .as("Expected proxy to remove the Dummy-Header specified in the Connection header")
              .isEmpty();
        }
    }

	@Test
	public void testProxyRemovesHopByHopHeadersFromClient() throws Exception {
		HttpRequest expectedServerRequest = request() //
				.withMethod("GET")
				.withPath("/connectionheaders")
				.withHeader(NottableString.not("proxy-authenticate"))
				.withHeader(NottableString.not("proxy-authorization"));
		mockServer.when(expectedServerRequest, Times.exactly(1))
				.respond(response().withStatusCode(200).withBody("success"));

		proxyServer = DefaultHttpProxyServer.bootstrap().withPort(0).start();

		try (CloseableHttpClient httpClient = createProxiedHttpClient(proxyServer.getListenAddress().getPort())) {
			HttpGet clientRequest = new HttpGet("http://localhost:" + mockServerPort + "/connectionheaders");
			clientRequest.addHeader("Proxy-Authenticate", "");
			clientRequest.addHeader("Proxy-Authorization", "");
			HttpResponse response = httpClient.execute(clientRequest);
			EntityUtils.consume(response.getEntity());
      assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
		}
	}
}
