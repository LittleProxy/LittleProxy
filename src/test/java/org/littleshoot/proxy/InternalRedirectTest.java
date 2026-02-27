package org.littleshoot.proxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Integration test for GitHub issue #68 - demonstrating how to implement internal redirects.
 *
 * <p>The question asks how to handle a 302 redirect internally, where the client never sees the 302
 * response - instead, the proxy follows the redirect and returns the final response.
 *
 * <p>Solution: Use an external HTTP client (like Apache HttpClient) to make a new request to the
 * redirect URL, then return that response as a short-circuit response to the client.
 */
class InternalRedirectTest {

  public static final int FREE_PORT = 0;
  @Nullable private HttpProxyServer proxyServer = null;
  @Nullable private WireMockServer wireMockServer = null;

  /** Sets up a WireMock server that returns a 302 redirect to /final. */
  @BeforeEach
  void setUpRedirectServer() {
    wireMockServer = new WireMockServer(options().dynamicPort());
    wireMockServer.start();

    // Stub: return 302 redirect to /final
    wireMockServer.stubFor(
        get(urlEqualTo("/redirect"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/final")));

    // Stub: return final response
    wireMockServer.stubFor(
        get(urlEqualTo("/final"))
            .willReturn(
                aResponse().withStatus(200).withBody("Final response from internal redirect!")));
  }

  @AfterEach
  void tearDown() {
    if (proxyServer != null) {
      proxyServer.abort();
    }
    if (wireMockServer != null) {
      wireMockServer.stop();
    }
  }

  /**
   * This test demonstrates how to implement an internal redirect: 1. Detect a 302 redirect in
   * serverToProxyResponse 2. Use an external HTTP client to make a new request to the redirect URL
   * 3. Return that response as a short-circuit response to the client
   *
   * <p>The client will never see the 302 - they'll only see the final response.
   */
  @Test
  @Timeout(10)
  void testInternalRedirectFollowsRedirectTransparently() throws IOException {
      assert wireMockServer != null;
      int wireMockPort = wireMockServer.port();

    // Create a filter that follows redirects internally
    HttpFiltersSource filtersSource =
        new HttpFiltersSourceAdapter() {
          @Override
          public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
            return new HttpFiltersAdapter(originalRequest) {
              @Override
              @NullMarked
              public HttpObject serverToProxyResponse(HttpObject httpObject) {
                if (httpObject instanceof HttpResponse response && HttpResponseStatus.FOUND.equals(response.status())
                      && response.headers().contains(HttpHeaderNames.LOCATION)) {
                    String location = response.headers().get(HttpHeaderNames.LOCATION);
                    assert originalRequest != null;
                    return followRedirect(originalRequest, location);
                  }

                return httpObject;
              }
            };
          }
        };

    proxyServer =
        DefaultHttpProxyServer.bootstrap().withPort(FREE_PORT).withFiltersSource(filtersSource).start();

    int proxyPort = proxyServer.getListenAddress().getPort();

    // Make a request that will trigger a redirect - use custom client to read body
    String body = "";
    int statusCode;
    try (CloseableHttpClient httpClient =
        HttpClients.custom()
            .setProxy(new org.apache.http.HttpHost("127.0.0.1", proxyPort))
            .build()) {
      HttpGet request = new HttpGet("http://127.0.0.1:" + wireMockPort + "/redirect");

      try (CloseableHttpResponse resp = httpClient.execute(request)) {
        statusCode = resp.getStatusLine().getStatusCode();
        HttpEntity entity = resp.getEntity();
        if (entity != null) {
          body = EntityUtils.toString(entity);
        }
      }
    }

    // The client should see the FINAL response (200 OK), NOT the redirect (302)
    assertThat(statusCode)
        .as("Client should receive the final response, not the redirect")
        .isEqualTo(200);
    assertThat(body).contains("Final response from internal redirect!");
  }

  /**
   * Helper method that follows a redirect by making a new HTTP request and returning the response
   * as a short-circuit response.
   *
   * <p>This is the key to solving issue #68 - you must use an external HTTP client since
   * LittleProxy's filter mechanism doesn't provide a way to "make a new request" directly.
   *
   * @param originalRequest The original HTTP request from the client (to extract the host)
   * @param location The Location header value from the 302 response (can be absolute or relative)
   * @return A short-circuit response to send to the client
   */
  private HttpResponse followRedirect(HttpRequest originalRequest, String location) {
    // Extract the original host from the request
    String originalHost = originalRequest.headers().get(HttpHeaderNames.HOST);
    if (originalHost == null) {
      originalHost = "127.0.0.1"; // Fallback
    }

    // Resolve the redirect URL - handle both absolute and relative Location headers
    String targetUrl = resolveRedirectUrl(location, originalHost);

    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpGet httpGet = new HttpGet(targetUrl);
      try (CloseableHttpResponse backendResponse = httpClient.execute(httpGet)) {
        int statusCode = backendResponse.getStatusLine().getStatusCode();
        HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(statusCode);

        String body = "";
        HttpEntity entity = backendResponse.getEntity();
        if (entity != null) {
          body = EntityUtils.toString(entity);
        }

        // Return a short-circuit response with the final response body
        DefaultFullHttpResponse response =
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus);
        response.content().writeBytes(body.getBytes());
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length());
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

        return response;
      }
    } catch (IOException e) {
      // Return an error response if the redirect fails
      return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY);
    }
  }

  /**
   * Resolves a redirect Location header to a full URL.
   *
   * <p>Handles both:- Absolute URLs (<a href="http://example.com/path">...</a>) - returned as-is - Relative URLs
   * (/path) - combined with the original host
   *
   * @param location The Location header value
   * @param originalHost The original request's Host header
   * @return The full URL to request
   */
  private String resolveRedirectUrl(String location, String originalHost) {
    if (location.startsWith("http://") || location.startsWith("https://")) {
      // Already an absolute URL
      return location;
    }

    // Parse the original host to separate hostname and port
    String host = originalHost;
    int port = 80; // Default HTTP port

    if (originalHost.contains(":")) {
      String[] parts = originalHost.split(":");
      host = parts[FREE_PORT];
      try {
        port = Integer.parseInt(parts[1]);
      } catch (NumberFormatException e) {
        port = 80;
      }
    }

    // Combine host and relative path
    if (location.startsWith("/")) {
      return "http://" + host + ":" + port + location;
    } else {
      return "http://" + host + ":" + port + "/" + location;
    }
  }
}
