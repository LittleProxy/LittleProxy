package org.littleshoot.proxy;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.SocketClientUtil;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.Delay;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.createProxiedHttpClient;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public final class TimeoutTest {

    private static final String UNUSED_URI_FOR_BAD_GATEWAY = "http://1.2.3.6:53540";

    private ClientAndServer mockServer;
    private int mockServerPort;

    private HttpProxyServer proxyServer;

    @BeforeEach
    void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
    }

    @AfterEach
    void tearDown() {
        try {
            if (mockServer != null) {
                mockServer.stop();
            }
        } finally {
            if (proxyServer != null) {
                proxyServer.abort();
            }
        }
    }

    @Test
    public void testIdleConnectionTimeout() throws Exception {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withIdleConnectionTimeout(1)
                .start();

        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/idleconnection"),
                Times.exactly(1))
                .respond(response()
                                .withStatusCode(200)
                                .withDelay(new Delay(TimeUnit.SECONDS, 5))
                );

        try (CloseableHttpClient httpClient = createProxiedHttpClient(proxyServer.getListenAddress().getPort())) {
            long start = System.nanoTime();
            HttpGet get = new HttpGet("http://127.0.0.1:" + mockServerPort + "/idleconnection");
            long stop = System.nanoTime();

            HttpResponse response = httpClient.execute(get);
            EntityUtils.consumeQuietly(response.getEntity());

          assertThat(response.getStatusLine().getStatusCode())
            .as("Expected to receive an HTTP 504 (Gateway Timeout) response after proxy did not receive a response within 1 second")
            .isEqualTo(504);
          assertThat(MILLISECONDS.convert(stop - start, TimeUnit.NANOSECONDS))
            .as("Expected idle connection timeout to happen after approximately 1 second")
            .isLessThan(2000L);
        }
    }

    @Test
    public void testConnectionTimeout() throws Exception {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withConnectTimeout(1000)
                .start();

        try (CloseableHttpClient httpClient = createProxiedHttpClient(proxyServer.getListenAddress().getPort())) {

            HttpGet get = new HttpGet(UNUSED_URI_FOR_BAD_GATEWAY);

            long start = System.nanoTime();
            HttpResponse response = httpClient.execute(get);
            long stop = System.nanoTime();

            EntityUtils.consumeQuietly(response.getEntity());

            assertThat(response.getStatusLine().getStatusCode())
                .as("Expected to receive an HTTP 502 (Bad Gateway) response after proxy could not connect within 1 second")
                .isEqualTo(502);
            assertThat(MILLISECONDS.convert(stop - start, TimeUnit.NANOSECONDS))
              .as("Expected connection timeout to happen after approximately 1 second")
              .isLessThan(2000L);
        }
    }

    /**
     * Verifies that when the client times out sending the initial request, the proxy still returns a Gateway Timeout.
     */
    @Test
    public void testClientIdleBeforeRequestReceived() throws IOException, InterruptedException {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withIdleConnectionTimeout(1)
                .start();

        // connect to the proxy and begin to transmit the request, but don't send the trailing \r\n that indicates the client has completely transmitted the request
        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1";

        // using the SocketClientUtil since we want the client to fail to send the entire GET request, which is not possible with
        // Apache HTTP client and most other HTTP clients
        Socket socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        SocketClientUtil.writeStringToSocket(successfulGet, socket);

        // wait a bit to allow the proxy server to respond
        Thread.sleep(1500);

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Client to proxy connection should be closed")
          .isFalse();

        socket.close();
    }
}
