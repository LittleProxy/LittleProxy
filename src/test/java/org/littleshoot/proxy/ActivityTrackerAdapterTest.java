package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityTrackerAdapterTest {

  private ActivityTrackerAdapter adapter;
  private FlowContext flowContext;
  private FullFlowContext fullFlowContext;
  private HttpRequest httpRequest;
  private HttpResponse httpResponse;
  private SSLSession sslSession;
  private InetSocketAddress clientAddress;

  @BeforeEach
  void setUp() {
    adapter = new ActivityTrackerAdapter();
    flowContext = mock(FlowContext.class);
    fullFlowContext = mock(FullFlowContext.class);
    httpRequest = mock(HttpRequest.class);
    httpResponse = mock(HttpResponse.class);
    sslSession = mock(SSLSession.class);
    clientAddress = new InetSocketAddress("127.0.0.1", 8080);
  }

  @Test
  void testBytesReceivedFromClient() {
    assertThatCode(() -> adapter.bytesReceivedFromClient(flowContext, 1024))
        .doesNotThrowAnyException();
  }

  @Test
  void testRequestReceivedFromClient() {
    assertThatCode(() -> adapter.requestReceivedFromClient(flowContext, httpRequest))
        .doesNotThrowAnyException();
  }

  @Test
  void testBytesSentToServer() {
    assertThatCode(() -> adapter.bytesSentToServer(fullFlowContext, 2048))
        .doesNotThrowAnyException();
  }

  @Test
  void testRequestSentToServer() {
    assertThatCode(() -> adapter.requestSentToServer(fullFlowContext, httpRequest))
        .doesNotThrowAnyException();
  }

  @Test
  void testBytesReceivedFromServer() {
    assertThatCode(() -> adapter.bytesReceivedFromServer(fullFlowContext, 4096))
        .doesNotThrowAnyException();
  }

  @Test
  void testResponseReceivedFromServer() {
    assertThatCode(() -> adapter.responseReceivedFromServer(fullFlowContext, httpResponse))
        .doesNotThrowAnyException();
  }

  @Test
  void testBytesSentToClient() {
    assertThatCode(() -> adapter.bytesSentToClient(flowContext, 1024)).doesNotThrowAnyException();
  }

  @Test
  void testResponseSentToClient() {
    assertThatCode(() -> adapter.responseSentToClient(flowContext, httpResponse))
        .doesNotThrowAnyException();
  }

  @Test
  void testClientConnected() {
    assertThatCode(() -> adapter.clientConnected(clientAddress)).doesNotThrowAnyException();
  }

  @Test
  void testClientSSLHandshakeSucceeded() {
    assertThatCode(() -> adapter.clientSSLHandshakeSucceeded(clientAddress, sslSession))
        .doesNotThrowAnyException();
  }

  @Test
  void testClientDisconnected() {
    assertThatCode(() -> adapter.clientDisconnected(clientAddress, sslSession))
        .doesNotThrowAnyException();
  }

  @Test
  void testImplementsActivityTracker() {
    assertThat(adapter).isInstanceOf(ActivityTracker.class);
  }
}
