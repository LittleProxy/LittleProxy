package org.littleshoot.proxy;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.createProxiedHttpClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Objects;
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

public final class MessageTerminationTest {
  private WireMockServer mockServer;
  private int mockServerPort;
  private HttpProxyServer proxyServer;

  @BeforeEach
  void setUp() {
    mockServer = new WireMockServer(options().dynamicPort());
    mockServer.start();
    mockServerPort = mockServer.port();
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
    // set up the server so that it indicates the end of the response by closing the
    // connection. the proxy
    // should automatically add the Transfer-Encoding: chunked header when sending
    // to the client.
    mockServer.stubFor(
        get(urlEqualTo("/"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("Success!")
                    .withHeader("Connection", "close")));

    proxyServer = DefaultHttpProxyServer.bootstrap().withPort(0).start();
    int proxyServerPort = proxyServer.getListenAddress().getPort();

    HttpClient httpClient = createProxiedHttpClient(proxyServerPort);
    HttpResponse response =
        httpClient.execute(new HttpGet("http://127.0.0.1:" + mockServerPort + "/"));

    assertThat(Objects.requireNonNull(response.getStatusLine()).getStatusCode())
        .as("Expected to receive a 200 from the server")
        .isEqualTo(200);

    // verify the Transfer-Encoding header was added
    Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
    assertThat(transferEncodingHeaders)
        .as("Expected to see a Transfer-Encoding header")
        .isNotEmpty();
    String transferEncoding = Objects.requireNonNull(transferEncodingHeaders[0].getValue());
    assertThat(transferEncoding)
        .as("Expected Transfer-Encoding to be chunked")
        .isEqualTo("chunked");

    String bodyString = EntityUtils.toString(response.getEntity(), "ISO-8859-1");
    response.getEntity().getContent().close();

    assertThat(bodyString).isEqualTo("Success!");
  }

  @Test
  public void testResponseWithContentLengthNotModified() throws Exception {
    // the proxy should not modify the response since it contains a Content-Length
    // header.
    mockServer.stubFor(
        get(urlEqualTo("/"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("Success!")
                    .withHeader("Connection", "close")
                    .withHeader("Content-Length", "8")));

    proxyServer = DefaultHttpProxyServer.bootstrap().withPort(0).start();
    int proxyServerPort = proxyServer.getListenAddress().getPort();

    HttpClient httpClient = createProxiedHttpClient(proxyServerPort);
    HttpResponse response =
        httpClient.execute(new HttpGet("http://127.0.0.1:" + mockServerPort + "/"));

    assertThat(Objects.requireNonNull(response.getStatusLine()).getStatusCode())
        .as("Expected to receive a 200 from the server")
        .isEqualTo(200);

    // verify the Transfer-Encoding header was NOT added
    Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
    assertThat(transferEncodingHeaders)
        .as("Did not expect to see a Transfer-Encoding header")
        .isEmpty();

    String bodyString = EntityUtils.toString(response.getEntity(), "ISO-8859-1");
    response.getEntity().getContent().close();

    assertThat(bodyString).isEqualTo("Success!");
  }

  @Test
  public void testFilterAddsContentLength() throws Exception {
    // when a filter with buffering is added to the filter chain, the aggregated
    // FullHttpResponse should
    // automatically have a Content-Length header
    mockServer.stubFor(
        get(urlEqualTo("/"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("Success!")
                    .withHeader("Connection", "close")
                    .withHeader("Content-Length", "8")));

    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withFiltersSource(
                new HttpFiltersSourceAdapter() {
                  @Override
                  public int getMaximumResponseBufferSizeInBytes() {
                    return 100000;
                  }
                })
            .withPort(0)
            .start();
    int proxyServerPort = proxyServer.getListenAddress().getPort();

    HttpClient httpClient = createProxiedHttpClient(proxyServerPort);
    HttpResponse response =
        httpClient.execute(new HttpGet("http://127.0.0.1:" + mockServerPort + "/"));

    assertThat(Objects.requireNonNull(response.getStatusLine()).getStatusCode())
        .as("Expected to receive a 200 from the server")
        .isEqualTo(200);

    // verify the Transfer-Encoding header was NOT added
    Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
    assertThat(transferEncodingHeaders)
        .as("Did not expect to see a Transfer-Encoding header")
        .isEmpty();

    Header[] contentLengthHeaders = response.getHeaders("Content-Length");
    assertThat(contentLengthHeaders).as("Expected to see a Content-Length header").isNotEmpty();

    String bodyString = EntityUtils.toString(response.getEntity(), "ISO-8859-1");
    response.getEntity().getContent().close();

    assertThat(bodyString).isEqualTo("Success!");
  }

  @Test
  public void testResponseToHEADNotModified() throws Exception {
    // the proxy should not modify the response since it is an HTTP HEAD request
    mockServer.stubFor(head(urlEqualTo("/")).willReturn(aResponse().withStatus(200)));

    proxyServer = DefaultHttpProxyServer.bootstrap().withPort(0).start();
    int proxyServerPort = proxyServer.getListenAddress().getPort();

    HttpClient httpClient = createProxiedHttpClient(proxyServerPort);
    HttpResponse response =
        httpClient.execute(new HttpHead("http://127.0.0.1:" + mockServerPort + "/"));

    assertThat(Objects.requireNonNull(response.getStatusLine()).getStatusCode())
        .as("Expected to receive a 200 from the server")
        .isEqualTo(200);

    // verify the Transfer-Encoding header was NOT added
    Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
    assertThat(transferEncodingHeaders)
        .as("Did not expect to see a Transfer-Encoding header")
        .isEmpty();

    // verify the Content-Length header was not added
    Header[] contentLengthHeaders = response.getHeaders("Content-Length");
    assertThat(contentLengthHeaders).as("Did not expect to see a Content-Length header").isEmpty();

    assertThat(response.getEntity())
        .as("Expected response to HEAD to have no entity body")
        .isNull();
  }
}
