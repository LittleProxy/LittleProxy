package org.littleshoot.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.Slf4jNotifier;
import com.github.tomakehurst.wiremock.http.Request;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.SocketClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.Options.DYNAMIC_PORT;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.allRequests;
import static com.github.tomakehurst.wiremock.matching.UrlPattern.ANY;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This class tests the proxy's keep alive/connection closure behavior.
 */
@Tag("slow-test")
@ParametersAreNonnullByDefault
public final class KeepAliveTest {
    private static final Logger log = LoggerFactory.getLogger(KeepAliveTest.class);
    private HttpProxyServer proxyServer;
    private final WireMockServer mockServer = new WireMockServer(wireMockConfig().port(DYNAMIC_PORT).notifier(new Slf4jNotifier(true)));

    private int mockServerPort;
    private Socket socket;

    @BeforeEach
    void setUp() {
        mockServer.resetAll();
        mockServer.addMockServiceRequestListener(KeepAliveTest::requestReceived);
        mockServer.start();
        mockServerPort = mockServer.port();

        log.info("Mock server port: {}", mockServerPort);
    }

    private static void requestReceived(Request inRequest,
                                          com.github.tomakehurst.wiremock.http.Response inResponse) {
        log.info("WireMock request at URL: {}", inRequest.getAbsoluteUrl());
        log.info("WireMock request headers: \n{}", inRequest.getHeaders());
        log.info("WireMock response body: \n{}", inResponse.getBodyAsString());
        log.info("WireMock response headers: \n{}", inResponse.getHeaders());
      try {
        String raw = IOUtils.toString(inResponse.getBodyStream(), StandardCharsets.UTF_8);
          log.info("WireMock response body raw: \n{}", raw);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (socket != null) {
                socket.close();
            }
        } finally {
            try {
                mockServer.stop();
            } finally {
                if (proxyServer != null) {
                    proxyServer.stop();
                }
            }
        }
    }

    /**
     * Tests that the proxy does not close the connection after a successful HTTP 1.1 GET request and response.
     */
    @Test
    public void testHttp11DoesNotCloseConnectionByDefault() throws IOException, InterruptedException {
        mockServer.stubFor(any(ANY).willReturn(ok("success-1")));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = httpGet(mockServerPort, "/load-1");

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), successfulGet);
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(750);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

            assertThat(response)
              .as("Expected to receive an HTTP 200 from the server (iteration: %s)", i)
              .startsWith("HTTP/1.1 200 OK");
            assertThat(response)
              .as("Unexpected message body (iteration: %s)", i)
              .contains("success-1");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
        mockServer.verify(2, getRequestedFor(urlEqualTo("/load-1")));
        mockServer.verify(2, anyRequestedFor(anyUrl()));
    }

    /**
     * Tests that the proxy keeps the connection to the client open after a server disconnect, even when the server is using
     * connection closure to indicate the end of a message.
     */
    @Test
    public void testProxyKeepsClientConnectionOpenAfterServerDisconnect() throws IOException, InterruptedException {
        mockServer.stubFor(WireMock.get("/load-2").willReturn(ok("success-2")
          .withHeader("Connection", "close")));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        // construct the basic request: METHOD + URI + HTTP version + CRLF (to indicate the end of the request)
        String successfulGet = httpGet(mockServerPort, "/load-2");

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), successfulGet);
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(750);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

            assertThat(response)
              .as("Expected to receive an HTTP 200 from the server (iteration: %s)", i)
              .startsWith("HTTP/1.1 200 OK");
            // the proxy will set the Transfer-Encoding to chunked since the server is using connection closure to indicate the end of the message.
            // (matching capitalized or lowercase Transfer-Encoding, since Netty 4.1+ uses lowercase header names)
            assertThat(response.toLowerCase(Locale.US))
              .as("Expected proxy to set Transfer-Encoding to chunked")
              .contains("transfer-encoding: chunked");
            // the Transfer-Encoding is chunked, so the body text will be followed by a 0 and 2 CRLFs
            assertThat(response)
              .as("Unexpected message body (iteration: %s)", i)
              .contains("success-2");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
        mockServer.verify(2, getRequestedFor(urlEqualTo("/load-2")));
        mockServer.verify(2, anyRequestedFor(anyUrl()));
    }

    /**
     * Tests that the proxy does not close the connection after a 502 Bad Gateway response.
     */
    @Test
    public void testBadGatewayDoesNotCloseConnection() throws IOException, InterruptedException {
        mockServer.stubFor(any(ANY).willReturn(ok("success-5")));

        proxyServer = DefaultHttpProxyServer.bootstrap()
          .withPort(0)
          .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String badGatewayGet = httpGet(0, "/load-5");

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), badGatewayGet);
            SocketClientUtil.writeStringToSocket(badGatewayGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(1500);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

            assertThat(response)
              .as("Expected to receive an HTTP 200 from the server (iteration: %s)", i)
              .startsWith("HTTP/1.1 502 Bad Gateway");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
        mockServer.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * Tests that the proxy does not close the connection after a 504 Gateway Timeout response.
     */
    @Test
    public void testGatewayTimeoutDoesNotCloseConnection() throws IOException {
        mockServer.stubFor(WireMock.get("/load-3").willReturn(ok("success-3")
          .withFixedDelay(secondsToMillis(10))));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withIdleConnectionTimeout(Duration.ofSeconds(2))
                .withPort(0)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = httpGet(mockServerPort, "/load-3");

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), successfulGet);
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

	          // match the whole response to make sure that it's not repeated
            assertThat(response).as("The response is repeated:").isEqualTo("HTTP/1.1 504 Gateway Timeout\r\n" +
                    "content-length: 15\r\n" +
                    "content-type: text/html; charset=utf-8\r\n" +
                    "\r\n" +
                    "Gateway Timeout");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
        mockServer.verify(2, getRequestedFor(urlEqualTo("/load-3")));
    }

    /**
     * Tests that the proxy does not close the connection by default after a short-circuit response.
     */
    @Test
    public void testShortCircuitResponseDoesNotCloseConnectionByDefault() throws IOException, InterruptedException {
        mockServer.stubFor(WireMock.get("/load-4").willReturn(aResponse()
          .withStatus(500)
          .withBody("this response should never be sent")));

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                        if (httpObject instanceof HttpRequest) {
                            HttpResponse shortCircuitResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                            HttpUtil.setContentLength(shortCircuitResponse, 0);
                            return shortCircuitResponse;
                        } else {
                            return null;
                        }
                    }
                };
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = httpGet(mockServerPort, "/load-4");

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), successfulGet);
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(750);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

            assertThat(response)
              .as("Expected to receive an HTTP 200 from the server (iteration: %s)", i)
              .startsWith("HTTP/1.1 200 OK");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
        mockServer.verify(0, allRequests());
    }

    /**
     * Tests that the proxy will close the connection after a short circuit response if the short circuit response
     * contains a Connection: close header.
     */
    @Test
    public void testShortCircuitResponseCanCloseConnection() throws IOException, InterruptedException {
        mockServer.stubFor(WireMock.get("/load-6").willReturn(
          aResponse().withStatus(500).withBody("this response should never be sent")));

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                        if (httpObject instanceof HttpRequest) {
                            HttpResponse shortCircuitResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                            HttpUtil.setContentLength(shortCircuitResponse, 0);
                            HttpUtil.setKeepAlive(shortCircuitResponse, false);
                            return shortCircuitResponse;
                        } else {
                            return null;
                        }
                    }
                };
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()
          .withPort(0)
          .withFiltersSource(filtersSource)
          .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = httpGet(mockServerPort, "/success-6");

        // only send this request once, since we expect the short circuit response to close the connection
        log.debug("Sending to socket {} packet '{}'...", socket.getLocalPort(), successfulGet);
        SocketClientUtil.writeStringToSocket(successfulGet, socket);

        // wait a bit to allow the proxy server to respond
        Thread.sleep(750);

        log.debug("Reading from socket {}...", socket.getLocalPort());
        String response = SocketClientUtil.readStringFromSocket(socket);

        assertThat(response)
          .as("Expected to receive an HTTP 200 from the server")
          .startsWith("HTTP/1.1 200 OK");

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be closed")
          .isFalse();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be closed")
          .isFalse();
        mockServer.verify(0, anyRequestedFor(anyUrl()));
    }

    private static int secondsToMillis(int seconds) {
        return (int) SECONDS.toMillis(seconds);
    }

    /**
     * construct the basic request: METHOD + URI + HTTP version + CRLF (to indicate the end of the request)
     */
    private String httpGet(int port, String path) {
        return String.format("GET http://localhost:%d%s HTTP/1.1\r\nHost: localhost\r\n\r\n", port, path);
    }
}

