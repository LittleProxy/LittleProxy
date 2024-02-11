package org.littleshoot.proxy;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.ConnectionOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public final class MessageTerminationTest {
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
        if (mockServer != null) {
            mockServer.stop();
        }

        if (proxyServer != null) {
            proxyServer.abort();
        }
    }

    @Test
    public void testResponseWithoutTerminationIsChunked() throws Exception {
        // set up the server so that it indicates the end of the response by closing the connection. the proxy
        // should automatically add the Transfer-Encoding: chunked header when sending to the client.
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/"),
                Times.unlimited())
                .respond(response()
                                .withStatusCode(200)
                                .withBody("Success!")
                                .withConnectionOptions(new ConnectionOptions()
                                        .withCloseSocket(true)
                                        .withSuppressConnectionHeader(true)
                                        .withSuppressContentLengthHeader(true))
                );

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        int proxyServerPort = proxyServer.getListenAddress().getPort();

        HttpClient httpClient = TestUtils.createProxiedHttpClient(proxyServerPort);
        HttpResponse response = httpClient.execute(new HttpGet("http://127.0.0.1:" + mockServerPort + "/"));

      assertThat(response.getStatusLine().getStatusCode())
        .as("Expected to receive a 200 from the server")
        .isEqualTo(200);

        // verify the Transfer-Encoding header was added
        Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
        assertThat(transferEncodingHeaders.length)
          .as("Expected to see a Transfer-Encoding header")
          .isGreaterThanOrEqualTo(1);
        String transferEncoding = transferEncodingHeaders[0].getValue();
        assertThat(transferEncoding).as("Expected Transfer-Encoding to be chunked").isEqualTo("chunked");

        String bodyString = EntityUtils.toString(response.getEntity(), "ISO-8859-1");
        response.getEntity().getContent().close();

      assertThat(bodyString).isEqualTo("Success!");
    }

    @Test
    public void testResponseWithContentLengthNotModified() throws Exception {
        // the proxy should not modify the response since it contains a Content-Length header.
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/"),
                Times.unlimited())
                .respond(response()
                                .withStatusCode(200)
                                .withBody("Success!")
                                .withConnectionOptions(new ConnectionOptions()
                                        .withCloseSocket(true)
                                        .withSuppressConnectionHeader(true))
                );

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        int proxyServerPort = proxyServer.getListenAddress().getPort();

        HttpClient httpClient = TestUtils.createProxiedHttpClient(proxyServerPort);
        HttpResponse response = httpClient.execute(new HttpGet("http://127.0.0.1:" + mockServerPort + "/"));

        assertThat(response.getStatusLine().getStatusCode()).as("Expected to receive a 200 from the server").isEqualTo(200);

        // verify the Transfer-Encoding header was NOT added
        Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
        assertThat(transferEncodingHeaders).as("Did not expect to see a Transfer-Encoding header").isEmpty();

        String bodyString = EntityUtils.toString(response.getEntity(), "ISO-8859-1");
        response.getEntity().getContent().close();

        assertThat(bodyString).isEqualTo("Success!");
    }

    @Test
    public void testFilterAddsContentLength() throws Exception {
        // when a filter with buffering is added to the filter chain, the aggregated FullHttpResponse should
        // automatically have a Content-Length header
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/"),
                Times.unlimited())
                .respond(response()
                                .withStatusCode(200)
                                .withBody("Success!")
                                .withConnectionOptions(new ConnectionOptions()
                                        .withCloseSocket(true)
                                        .withSuppressConnectionHeader(true)
                                        .withSuppressContentLengthHeader(true))
                );

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public int getMaximumResponseBufferSizeInBytes() {
                        return 100000;
                    }
                })
                .withPort(0)
                .start();
        int proxyServerPort = proxyServer.getListenAddress().getPort();


        HttpClient httpClient = TestUtils.createProxiedHttpClient(proxyServerPort);
        HttpResponse response = httpClient.execute(new HttpGet("http://127.0.0.1:" + mockServerPort + "/"));

        assertThat(response.getStatusLine().getStatusCode())
          .as("Expected to receive a 200 from the server")
          .isEqualTo(200);

        // verify the Transfer-Encoding header was NOT added
        Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
        assertThat(transferEncodingHeaders).as("Did not expect to see a Transfer-Encoding header").isEmpty();

        Header[] contentLengthHeaders = response.getHeaders("Content-Length");
        assertThat(contentLengthHeaders.length).as("Expected to see a Content-Length header").isGreaterThanOrEqualTo(1);

        String bodyString = EntityUtils.toString(response.getEntity(), "ISO-8859-1");
        response.getEntity().getContent().close();

        assertThat(bodyString).isEqualTo("Success!");
    }

    @Test
    public void testResponseToHEADNotModified() throws Exception {
        // the proxy should not modify the response since it is an HTTP HEAD request
        mockServer.when(request()
                        .withMethod("HEAD")
                        .withPath("/"),
                Times.unlimited())
                .respond(response()
                                .withStatusCode(200)
                                .withConnectionOptions(new ConnectionOptions()
                                        .withCloseSocket(false)
                                        .withSuppressConnectionHeader(true)
                                        .withSuppressContentLengthHeader(true))
                );

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        int proxyServerPort = proxyServer.getListenAddress().getPort();

        HttpClient httpClient = TestUtils.createProxiedHttpClient(proxyServerPort);
        HttpResponse response = httpClient.execute(new HttpHead("http://127.0.0.1:" + mockServerPort + "/"));

        assertThat(response.getStatusLine().getStatusCode()).as("Expected to receive a 200 from the server").isEqualTo(200);

        // verify the Transfer-Encoding header was NOT added
        Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
        assertThat(transferEncodingHeaders).as("Did not expect to see a Transfer-Encoding header").isEmpty();

        // verify the Content-Length header was not added
        Header[] contentLengthHeaders = response.getHeaders("Content-Length");
        assertThat(contentLengthHeaders).as("Did not expect to see a Content-Length header").isEmpty();

        assertThat(response.getEntity()).as("Expected response to HEAD to have no entity body").isNull();
    }
}
