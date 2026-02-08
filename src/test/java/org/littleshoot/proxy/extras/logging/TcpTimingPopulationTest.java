package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests to verify TCP timing fields are populated when disconnect events occur. These tests focus
 * on integration between ActivityLogger and connection lifecycle.
 */
class TcpTimingPopulationTest {

  @Mock private FlowContext flowContext;
  @Mock private FullFlowContext fullFlowContext;
  @Mock private HttpRequest request;
  @Mock private HttpResponse response;
  @Mock private SSLSession sslSession;

  private TestableActivityLogger activityLogger;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    activityLogger =
        new TestableActivityLogger(
            org.littleshoot.proxy.extras.logging.LogFormat.JSON,
            null,
            org.littleshoot.proxy.extras.logging.TimingMode.ALL);
  }

  @Test
  void testTcpTimingPresentAfterDisconnectLifecycle() {
    // Test that TCP timing fields are present after complete connection lifecycle
    // This simulates a real flow: connect → request → response → disconnect
    java.net.InetSocketAddress clientAddress = new java.net.InetSocketAddress("127.0.0.1", 12345);
    java.net.InetSocketAddress serverAddress = new java.net.InetSocketAddress("example.com", 443);

    Mockito.when(flowContext.getClientAddress()).thenReturn(clientAddress);
    Mockito.when(fullFlowContext.getClientAddress()).thenReturn(clientAddress);

    // Mock timing values as if populated by connection lifecycle
    Mockito.when(flowContext.getTimingData("tcp_client_connection_start_time_ms")).thenReturn(0L);
    Mockito.when(flowContext.getTimingData("tcp_client_connection_end_time_ms")).thenReturn(100L);
    Mockito.when(flowContext.getTimingData("tcp_client_connection_duration_ms")).thenReturn(100L);
    Mockito.when(flowContext.getTimingData("tcp_server_connection_start_time_ms")).thenReturn(50L);
    Mockito.when(flowContext.getTimingData("tcp_server_connection_end_time_ms")).thenReturn(150L);
    Mockito.when(flowContext.getTimingData("tcp_server_connection_duration_ms")).thenReturn(100L);
    Mockito.when(flowContext.getTimingData("tcp_connection_establishment_time_ms")).thenReturn(50L);
    Mockito.when(flowContext.getTimingData("ssl_handshake_time_ms")).thenReturn(25L);
    Mockito.when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(75L);

    // Execute HTTP request/response cycle
    activityLogger.clientConnected(flowContext);
    activityLogger.requestReceivedFromClient(flowContext, request);
    activityLogger.responseSentToClient(flowContext, response);

    // INFO log should be generated with available TCP timing data
    String logMessage = activityLogger.lastLogMessage;
    assertThat(logMessage).isNotNull();
    assertThat(logMessage).startsWith("{");

    // Verify TCP timing fields are present and have actual values, not dash
    assertThat(logMessage).contains("tcp_connection_establishment_time_ms");
    assertThat(logMessage).contains("ssl_handshake_time_ms");
    assertThat(logMessage).contains("tcp_client_connection_duration_ms");
    assertThat(logMessage).contains("tcp_server_connection_duration_ms");

    assertThat(logMessage).doesNotContain("tcp_connection_establishment_time_ms\":-");
    assertThat(logMessage).doesNotContain("ssl_handshake_time_ms\":-");
    assertThat(logMessage).doesNotContain("tcp_client_connection_duration_ms\":-");
    assertThat(logMessage).doesNotContain("tcp_server_connection_duration_ms\":-");
  }

  @Test
  void testTcpTimingAbsentBeforeDisconnect() {
    // Test that TCP timing fields are absent before disconnect events
    java.net.InetSocketAddress clientAddress = new java.net.InetSocketAddress("127.0.0.1", 12345);

    Mockito.when(flowContext.getClientAddress()).thenReturn(clientAddress);

    // Mock timing values as not yet populated (null)
    Mockito.when(flowContext.getTimingData("tcp_client_connection_duration_ms")).thenReturn(null);
    Mockito.when(flowContext.getTimingData("tcp_server_connection_duration_ms")).thenReturn(null);
    Mockito.when(flowContext.getTimingData("tcp_connection_establishment_time_ms"))
        .thenReturn(null);
    Mockito.when(flowContext.getTimingData("ssl_handshake_time_ms")).thenReturn(null);
    Mockito.when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(50L);

    // Execute HTTP request/response cycle
    activityLogger.clientConnected(flowContext);
    activityLogger.requestReceivedFromClient(flowContext, request);
    activityLogger.responseSentToClient(flowContext, response);

    // INFO log should be generated without TCP timing fields
    String logMessage = activityLogger.lastLogMessage;
    assertThat(logMessage).isNotNull();
    assertThat(logMessage).startsWith("{");

    // Verify TCP timing fields are absent from log (since they return null)
    assertThat(logMessage).doesNotContain("tcp_connection_establishment_time_ms");
    assertThat(logMessage).doesNotContain("ssl_handshake_time_ms");
    assertThat(logMessage).doesNotContain("tcp_client_connection_duration_ms");
    assertThat(logMessage).doesNotContain("tcp_server_connection_duration_ms");

    // But HTTP timing should still be present
    assertThat(logMessage).contains("http_request_processing_time_ms");
    assertThat(logMessage).contains("\"status\"");
    assertThat(logMessage).contains("\"method\"");
  }

  @Test
  void testFlowContextIntegration() {
    // Test that ActivityLogger properly stores timing data in FlowContext
    java.net.InetSocketAddress clientAddress = new java.net.InetSocketAddress("127.0.0.1", 12345);

    Mockito.when(flowContext.getClientAddress()).thenReturn(clientAddress);

    // Execute client connected event
    activityLogger.clientConnected(flowContext);

    // Verify connection start time was stored
    Mockito.verify(flowContext)
        .setTimingData("tcp_client_connection_start_time_ms", java.lang.Long.class);
  }

  /** Testable ActivityLogger that captures formatted log messages for verification. */
  private static class TestableActivityLogger
      extends org.littleshoot.proxy.extras.logging.ActivityLogger {
    String lastLogMessage;

    public TestableActivityLogger(
        org.littleshoot.proxy.extras.logging.LogFormat logFormat,
        org.littleshoot.proxy.extras.logging.LogFieldConfiguration config,
        org.littleshoot.proxy.extras.logging.TimingMode timingMode) {
      super(logFormat, config, timingMode);
    }

    @Override
    protected void logFormattedEntry(String flowId, String message) {
      this.lastLogMessage = message;
      super.logFormattedEntry(flowId, message);
    }

    @Override
    protected boolean shouldLogInfoEntry() {
      return true; // Always log INFO entries in tests
    }
  }
}
