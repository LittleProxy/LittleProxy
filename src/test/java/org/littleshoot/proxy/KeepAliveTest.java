package org.littleshoot.proxy;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.SocketClientUtil;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.ConnectionOptions;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * This class tests the proxy's keep alive/connection closure behavior.
 */
@Tag("slow-test")
@ParametersAreNonnullByDefault
public final class KeepAliveTest {
    private HttpProxyServer proxyServer;
    private ClientAndServer mockServer;
    private int mockServerPort;
    private Socket socket;

    @BeforeEach
    void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
        socket = null;
        proxyServer = null;
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (proxyServer != null) {
                proxyServer.abort();
            }
        } finally {
            try {
                if (mockServer != null) {
                    mockServer.stop();
                }
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }

    /**
     * Tests that the proxy does not close the connection after a successful HTTP 1.1 GET request and response.
     */
    @Test
    public void testHttp11DoesNotCloseConnectionByDefault() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"),
                Times.exactly(2))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success"));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        // construct the basic request: METHOD + URI + HTTP version + CRLF (to indicate the end of the request)
        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(750);

            String response = SocketClientUtil.readStringFromSocket(socket);

            assertThat(response)
              .as("Expected to receive an HTTP 200 from the server (iteration: %s)", i)
              .startsWith("HTTP/1.1 200 OK");
            assertThat(response)
              .as("Unexpected message body (iteration: %s)", i)
              .endsWith("success");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
    }

    /**
     * Tests that the proxy keeps the connection to the client open after a server disconnect, even when the server is using
     * connection closure to indicate the end of a message.
     */
    @Test
    public void testProxyKeepsClientConnectionOpenAfterServerDisconnect() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"),
                Times.exactly(2))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success")
                        .withConnectionOptions(new ConnectionOptions()
                                .withKeepAliveOverride(false)
                                .withSuppressContentLengthHeader(true)
                                .withCloseSocket(true)));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        // construct the basic request: METHOD + URI + HTTP version + CRLF (to indicate the end of the request)
        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(750);

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
              .contains("success");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
    }

    /**
     * Tests that the proxy does not close the connection after a 502 Bad Gateway response.
     */
    @Test
    public void testBadGatewayDoesNotCloseConnection() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success"));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String badGatewayGet = "GET http://localhost:0/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            SocketClientUtil.writeStringToSocket(badGatewayGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(1500);

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
    }

    /**
     * Tests that the proxy does not close the connection after a 504 Gateway Timeout response.
     */
    @Test
    public void testGatewayTimeoutDoesNotCloseConnection() throws IOException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"),
                Times.exactly(2))
                .respond(response()
                        .withStatusCode(200)
                        .withDelay(TimeUnit.SECONDS, 10)
                        .withBody("success"));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withIdleConnectionTimeout(2)
                .withPort(0)
                .start();

        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

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
    }

    /**
     * Tests that the proxy does not close the connection by default after a short-circuit response.
     */
    @Test
    public void testShortCircuitResponseDoesNotCloseConnectionByDefault() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(500)
                        .withBody("this response should never be sent"));

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

        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(750);

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
    }

    /**
     * Tests that the proxy will close the connection after a short circuit response if the short circuit response
     * contains a Connection: close header.
     */
    @Test
    public void testShortCircuitResponseCanCloseConnection() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(500)
                        .withBody("this response should never be sent"));

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

        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // only send this request once, since we expect the short circuit response to close the connection
        SocketClientUtil.writeStringToSocket(successfulGet, socket);

        // wait a bit to allow the proxy server to respond
        Thread.sleep(750);

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
    }
}

