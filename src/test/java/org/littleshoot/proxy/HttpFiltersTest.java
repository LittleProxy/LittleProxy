package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** Tests for HttpFilters interface. */
class HttpFiltersTest {

  @Test
  void testInterfaceDefinition() {
    assertThat(HttpFilters.class).isInterface();
  }

  @Test
  void testHasClientToProxyRequestMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("clientToProxyRequest", HttpObject.class)).isNotNull();
  }

  @Test
  void testHasProxyToServerRequestMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerRequest", HttpObject.class)).isNotNull();
  }

  @Test
  void testHasProxyToServerRequestSendingMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerRequestSending")).isNotNull();
  }

  @Test
  void testHasProxyToServerRequestSentMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerRequestSent")).isNotNull();
  }

  @Test
  void testHasServerToProxyResponseMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("serverToProxyResponse", HttpObject.class)).isNotNull();
  }

  @Test
  void testHasServerToProxyResponseTimedOutMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("serverToProxyResponseTimedOut")).isNotNull();
  }

  @Test
  void testHasServerToProxyResponseReceivingMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("serverToProxyResponseReceiving")).isNotNull();
  }

  @Test
  void testHasServerToProxyResponseReceivedMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("serverToProxyResponseReceived")).isNotNull();
  }

  @Test
  void testHasProxyToClientResponseMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToClientResponse", HttpObject.class)).isNotNull();
  }

  @Test
  void testHasProxyToServerConnectionQueuedMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerConnectionQueued")).isNotNull();
  }

  @Test
  void testHasProxyToServerResolutionStartedMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerResolutionStarted", String.class))
        .isNotNull();
  }

  @Test
  void testHasProxyToServerResolutionFailedMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerResolutionFailed", String.class))
        .isNotNull();
  }

  @Test
  void testHasProxyToServerResolutionSucceededMethod() throws NoSuchMethodException {
    assertThat(
            HttpFilters.class.getMethod(
                "proxyToServerResolutionSucceeded", String.class, InetSocketAddress.class))
        .isNotNull();
  }

  @Test
  void testHasProxyToServerConnectionStartedMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerConnectionStarted")).isNotNull();
  }

  @Test
  void testHasProxyToServerConnectionSSLHandshakeStartedMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerConnectionSSLHandshakeStarted"))
        .isNotNull();
  }

  @Test
  void testHasProxyToServerConnectionFailedMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerConnectionFailed")).isNotNull();
  }

  @Test
  void testHasProxyToServerConnectionSucceededMethod() throws NoSuchMethodException {
    assertThat(
            HttpFilters.class.getMethod(
                "proxyToServerConnectionSucceeded", ChannelHandlerContext.class))
        .isNotNull();
  }

  @Test
  void testHasProxyToServerAllowMitmMethod() throws NoSuchMethodException {
    assertThat(HttpFilters.class.getMethod("proxyToServerAllowMitm")).isNotNull();
    assertThat(HttpFilters.class.getMethod("proxyToServerAllowMitm").getReturnType())
        .isEqualTo(boolean.class);
  }

  @Test
  void testClientToProxyRequestReturnType() throws NoSuchMethodException {
    assertThat(
            HttpFilters.class.getMethod("clientToProxyRequest", HttpObject.class).getReturnType())
        .isEqualTo(HttpResponse.class);
  }

  @Test
  void testProxyToServerRequestReturnType() throws NoSuchMethodException {
    assertThat(
            HttpFilters.class.getMethod("proxyToServerRequest", HttpObject.class).getReturnType())
        .isEqualTo(HttpResponse.class);
  }

  @Test
  void testServerToProxyResponseReturnType() throws NoSuchMethodException {
    assertThat(
            HttpFilters.class.getMethod("serverToProxyResponse", HttpObject.class).getReturnType())
        .isEqualTo(HttpObject.class);
  }

  @Test
  void testProxyToClientResponseReturnType() throws NoSuchMethodException {
    assertThat(
            HttpFilters.class.getMethod("proxyToClientResponse", HttpObject.class).getReturnType())
        .isEqualTo(HttpObject.class);
  }

  @Test
  void testProxyToServerResolutionStartedReturnType() throws NoSuchMethodException {
    assertThat(
            HttpFilters.class
                .getMethod("proxyToServerResolutionStarted", String.class)
                .getReturnType())
        .isEqualTo(InetSocketAddress.class);
  }
}
