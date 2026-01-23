package org.littleshoot.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.test.HttpClientUtil.performLocalHttpGet;

public final class ClonedProxyTest {
    private WireMockServer mockServer;
    private int mockServerPort;

    private HttpProxyServer originalProxy;
    private HttpProxyServer clonedProxy;

    @BeforeEach
    void setUp() {
        mockServer = new WireMockServer(options().dynamicPort());
        mockServer.start();
        mockServerPort = mockServer.port();
    }

    @AfterEach
    void tearDown() {
        try {
            if (mockServer != null) {
                mockServer.stop();
            }
        } finally {
            try {
                if (originalProxy != null) {
                    originalProxy.abort();
                }
            } finally {
                if (clonedProxy != null) {
                    clonedProxy.abort();
                }
            }
        }
    }

    @Test
    public void testClonedProxyHandlesRequests() {
        originalProxy = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withName("original")
                .start();
        clonedProxy = originalProxy.clone()
                .withName("clone")
                .start();

        mockServer.stubFor(get(urlEqualTo("/testClonedProxyHandlesRequests"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("success")));

        HttpResponse response = performLocalHttpGet(mockServerPort, "/testClonedProxyHandlesRequests", clonedProxy);
        assertThat(response.getStatusLine().getStatusCode())
                .as("Expected to receive a 200 when making a request using the cloned proxy server")
                .isEqualTo(200);
    }

    @Test
    public void testStopClonedProxyDoesNotStopOriginalServer() {
        originalProxy = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withName("original")
                .start();
        clonedProxy = originalProxy.clone()
                .withName("clone")
                .start();

        clonedProxy.abort();

        mockServer.stubFor(get(urlEqualTo("/testClonedProxyHandlesRequests"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("success")));

        HttpResponse response = performLocalHttpGet(mockServerPort, "/testClonedProxyHandlesRequests", originalProxy);
        assertThat(response.getStatusLine().getStatusCode())
                .as("Expected to receive a 200 when making a request using the cloned proxy server")
                .isEqualTo(200);
    }

    @Test
    public void testStopOriginalServerDoesNotStopClonedServer() {
        originalProxy = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withName("original")
                .start();
        clonedProxy = originalProxy.clone()
                .withName("clone")
                .start();

        originalProxy.abort();

        mockServer.stubFor(get(urlEqualTo("/testClonedProxyHandlesRequests"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("success")));

        HttpResponse response = performLocalHttpGet(mockServerPort, "/testClonedProxyHandlesRequests", clonedProxy);
        assertThat(response.getStatusLine().getStatusCode())
                .as("Expected to receive a 200 when making a request using the cloned proxy server")
                .isEqualTo(200);
    }
}
