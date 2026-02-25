package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/**
 * Tests for ActivityTracker interface. Since ActivityTracker is an interface, these tests document
 * the expected behavior and verify the interface contract.
 */
class ActivityTrackerTest {

  @Test
  void testInterfaceDefinition() {
    assertThat(ActivityTracker.class).isInterface();
  }

  @Test
  void testHasClientConnectedMethod() throws NoSuchMethodException {
    assertThat(ActivityTracker.class.getMethod("clientConnected", InetSocketAddress.class))
        .isNotNull();
  }

  @Test
  void testHasClientSSLHandshakeSucceededMethod() throws NoSuchMethodException {
    assertThat(
            ActivityTracker.class.getMethod(
                "clientSSLHandshakeSucceeded", InetSocketAddress.class, SSLSession.class))
        .isNotNull();
  }

  @Test
  void testHasClientDisconnectedMethod() throws NoSuchMethodException {
    assertThat(
            ActivityTracker.class.getMethod(
                "clientDisconnected", InetSocketAddress.class, SSLSession.class))
        .isNotNull();
  }

  @Test
  void testHasBytesReceivedFromClientMethod() throws NoSuchMethodException {
    assertThat(
            ActivityTracker.class.getMethod(
                "bytesReceivedFromClient", FlowContext.class, int.class))
        .isNotNull();
  }

  @Test
  void testHasRequestReceivedFromClientMethod() throws NoSuchMethodException {
    assertThat(
            ActivityTracker.class.getMethod(
                "requestReceivedFromClient", FlowContext.class, HttpRequest.class))
        .isNotNull();
  }

  @Test
  void testHasBytesSentToServerMethod() throws NoSuchMethodException {
    assertThat(
            ActivityTracker.class.getMethod("bytesSentToServer", FullFlowContext.class, int.class))
        .isNotNull();
  }

  @Test
  void testHasRequestSentToServerMethod() throws NoSuchMethodException {
    assertThat(
            ActivityTracker.class.getMethod(
                "requestSentToServer", FullFlowContext.class, HttpRequest.class))
        .isNotNull();
  }

  @Test
  void testHasBytesReceivedFromServerMethod() throws NoSuchMethodException {
    assertThat(
            ActivityTracker.class.getMethod(
                "bytesReceivedFromServer", FullFlowContext.class, int.class))
        .isNotNull();
  }

  @Test
  void testHasResponseReceivedFromServerMethod() throws NoSuchMethodException {
    assertThat(
            ActivityTracker.class.getMethod(
                "responseReceivedFromServer", FullFlowContext.class, HttpResponse.class))
        .isNotNull();
  }

  @Test
  void testHasBytesSentToClientMethod() throws NoSuchMethodException {
    assertThat(ActivityTracker.class.getMethod("bytesSentToClient", FlowContext.class, int.class))
        .isNotNull();
  }

  @Test
  void testHasResponseSentToClientMethod() throws NoSuchMethodException {
    assertThat(
            ActivityTracker.class.getMethod(
                "responseSentToClient", FlowContext.class, HttpResponse.class))
        .isNotNull();
  }

  @Test
  void testAllMethodsReturnVoid() {
    for (var method : ActivityTracker.class.getMethods()) {
      assertThat(method.getReturnType()).isEqualTo(void.class);
    }
  }
}
