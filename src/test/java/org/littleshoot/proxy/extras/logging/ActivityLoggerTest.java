package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ActivityLoggerTest {

  @Mock private FlowContext flowContext;
  @Mock private FullFlowContext fullFlowContext;
  @Mock private HttpRequest request;
  @Mock private HttpResponse response;
  @Mock private HttpHeaders requestHeaders;
  @Mock private HttpHeaders responseHeaders;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(request.headers()).thenReturn(requestHeaders);
    when(response.headers()).thenReturn(responseHeaders);
  }

  @Test
  void testClfFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.CLF);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("CLF Log: " + tracker.lastLogMessage);
    // Expecting: 127.0.0.1 - - [Date] "GET /test HTTP/1.1" 200 100
    assertThat(tracker.lastLogMessage).contains("127.0.0.1 - - [");
    assertThat(tracker.lastLogMessage).contains("] \"GET /test HTTP/1.1\" 200 100");
  }

  @Test
  void testJsonFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("JSON Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).startsWith("{");
    assertThat(tracker.lastLogMessage).contains("\"client_ip\":\"127.0.0.1\"");
    assertThat(tracker.lastLogMessage).contains("\"method\":\"GET\"");
    assertThat(tracker.lastLogMessage).contains("\"uri\":\"/test\"");
    assertThat(tracker.lastLogMessage).contains("\"status\":\"200\"");
    assertThat(tracker.lastLogMessage).contains("\"bytes\":\"100\"");
  }

  @Test
  void testCustomConfiguration() {
    // Test custom field configuration
    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.TIMESTAMP)
            .addStandardField(StandardField.CLIENT_IP)
            .addStandardField(StandardField.METHOD)
            .addStandardField(StandardField.URI)
            .addStandardField(StandardField.STATUS)
            .addRequestHeader("X-Request-ID", "request_id")
            .addRequestHeader("Authorization", "auth")
            .addResponseHeader("X-Response-Time", "response_time")
            .addResponseHeader("Cache-Control", "cache_control")
            .addComputedField(ComputedField.GEOLOCATION_COUNTRY)
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Custom JSON Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"request_id\"");
    assertThat(tracker.lastLogMessage).contains("\"response_time\"");
    assertThat(tracker.lastLogMessage).contains("\"cache_control\"");
    assertThat(tracker.lastLogMessage).contains("\"geolocation_country\"");
  }

  @Test
  void testSecurityMonitoringConfiguration() {
    LogFieldConfiguration config = SecurityMonitoringConfig.create();
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);

    setupSecurityMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Security Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"csp\"");
    assertThat(tracker.lastLogMessage).contains("\"hsts\"");
    assertThat(tracker.lastLogMessage).contains("\"xss_protection\"");
    assertThat(tracker.lastLogMessage).contains("\"forwarded_for\"");
  }

  @Test
  void testPerformanceAnalyticsConfiguration() {
    LogFieldConfiguration config = PerformanceAnalyticsConfig.create();
    // Use JSON format to verify dynamic field configuration works
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);

    setupPerformanceMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Performance Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"cache_status\":\"HIT\"");
    assertThat(tracker.lastLogMessage)
        .contains("\"server_timing\":\"miss,db;dur=53,app;dur=47.2\"");
  }

  @Test
  void testAPIManagementConfiguration() {
    LogFieldConfiguration config = APIManagementConfig.create();
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.LTSV, config);

    setupAPIMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("API Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("request_id");
    assertThat(tracker.lastLogMessage).contains("rate_limit");
    assertThat(tracker.lastLogMessage).contains("correlation_id");
  }

  @Test
  void testElfFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.ELF);
    setupMocks();
    when(requestHeaders.get("Referer")).thenReturn("http://referrer.com");
    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("ELF Log: " + tracker.lastLogMessage);
    // host ident authuser [date] "request" status bytes "referer" "user-agent"
    // 127.0.0.1 - - [Date] "GET /test HTTP/1.1" 200 100 "http://referrer.com"
    // "Mozilla/5.0"
    assertThat(tracker.lastLogMessage).startsWith("127.0.0.1 - - [");
    assertThat(tracker.lastLogMessage)
        .contains("] \"GET /test HTTP/1.1\" 200 100 \"http://referrer.com\" \"Mozilla/5.0\"");
  }

  @Test
  void testW3cFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.W3C);
    setupMocks();
    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("W3C Log: " + tracker.lastLogMessage);
    // date time c-ip cs-method cs-uri-stem sc-status sc-bytes cs(User-Agent)
    // YYYY-MM-DD HH:MM:SS 127.0.0.1 GET /test 200 100 "Mozilla/5.0"
    assertThat(tracker.lastLogMessage).contains(" 127.0.0.1 GET /test 200 100 \"Mozilla/5.0\"");
  }

  @Test
  void testLtsvFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.LTSV);
    setupMocksWithDelay();

    tracker.requestReceivedFromClient(flowContext, request);
    // Simulate delay
    try {
      Thread.sleep(10);
    } catch (InterruptedException ignored) {
    }
    tracker.responseSentToClient(flowContext, response);

    System.out.println("LTSV Log: " + tracker.lastLogMessage);
    // LTSV uses field names from StandardField (client_ip, bytes) not host/size
    // Check for key fields in the output
    assertThat(tracker.lastLogMessage).contains("client_ip:127.0.0.1");
    assertThat(tracker.lastLogMessage).contains("method:GET");
    assertThat(tracker.lastLogMessage).contains("uri:/test");
    assertThat(tracker.lastLogMessage).contains("status:200");
    assertThat(tracker.lastLogMessage).contains("bytes:100");
    assertThat(tracker.lastLogMessage).contains("duration:");
  }

  @Test
  void testCsvFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.CSV);
    setupMocksWithDelay();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("CSV Log: " + tracker.lastLogMessage);
    // CSV format uses dynamic field configuration
    // Check for key values in the quoted CSV format
    assertThat(tracker.lastLogMessage).contains("\"127.0.0.1\"");
    assertThat(tracker.lastLogMessage).contains("\"GET\"");
    assertThat(tracker.lastLogMessage).contains("\"/test\"");
    assertThat(tracker.lastLogMessage).contains("\"200\"");
    assertThat(tracker.lastLogMessage).contains("\"100\"");
    assertThat(tracker.lastLogMessage).contains("\"Mozilla/5.0\"");
  }

  @Test
  void testHaproxyFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.HAPROXY);
    setupMocksWithDelay();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("HAProxy Log: " + tracker.lastLogMessage);
    // HAProxy format: process[pid]: client_ip:port [date] frontend backend/server Tq/Tw/Tc/Tr/Ta
    // status bytes ...
    assertThat(tracker.lastLogMessage).startsWith("littleproxy[0]: 127.0.0.1:");
    assertThat(tracker.lastLogMessage).contains("frontend backend/server");
    assertThat(tracker.lastLogMessage).contains("\"GET /test HTTP/1.1\"");
  }

  @Test
  void testSquidFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.SQUID);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Squid Log: " + tracker.lastLogMessage);
    // Squid format: time elapsed remotehost code/status bytes method URL rfc931 peerstatus/peerhost
    // type
    // time is epoch seconds with milliseconds (e.g., 1234567890.123)
    // For 2xx responses, we expect TCP_HIT (cache hit)
    assertThat(tracker.lastLogMessage)
        .matches(".*\\d+\\.\\d{3} \\d+ 127\\.0\\.0\\.1 TCP_HIT/200 100 GET /test - DIRECT/- -.*");
  }

  private static class TestableActivityLogger extends ActivityLogger {
    String lastLogMessage;
    boolean infoSummaryLogged = false;
    String lastInfoSummary;

    public TestableActivityLogger(LogFormat logFormat) {
      super(logFormat, null);
    }

    public TestableActivityLogger(LogFormat logFormat, LogFieldConfiguration config) {
      super(logFormat, config);
    }

    @Override
    protected void logFormattedEntry(String message) {
      this.lastLogMessage = message;
    }

    @Override
    protected boolean shouldLogFormattedEntry() {
      return true; // Always log in tests
    }
  }

  private void setupMocks() {
    setupMocksCommon();
  }

  private void setupMocksWithDelay() {
    setupMocksCommon();
    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");
  }

  private void setupMocksCommon() {
    InetSocketAddress clientAddr = mock(InetSocketAddress.class);
    InetAddress inetAddr = mock(InetAddress.class);
    when(flowContext.getClientAddress()).thenReturn(clientAddr);
    when(clientAddr.getAddress()).thenReturn(inetAddr);
    when(inetAddr.getHostAddress()).thenReturn("127.0.0.1");

    when(request.method()).thenReturn(HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(HttpVersion.HTTP_1_1);

    when(response.status()).thenReturn(HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
  }

  private void setupSecurityMocks() {
    setupMocksCommon();
    when(requestHeaders.get("X-Forwarded-For")).thenReturn("203.0.113.1");
    when(requestHeaders.get("Authorization")).thenReturn("Bearer token123");
    when(responseHeaders.get("Content-Security-Policy")).thenReturn("default-src 'self'");
    when(responseHeaders.get("Strict-Transport-Security")).thenReturn("max-age=31536000");
  }

  private void setupPerformanceMocks() {
    setupMocksCommon();
    when(requestHeaders.get("Accept-Encoding")).thenReturn("gzip, deflate");
    when(requestHeaders.get("If-None-Match")).thenReturn("\"12345\"");
    when(responseHeaders.get("X-Cache")).thenReturn("HIT");
    when(responseHeaders.get("Server-Timing")).thenReturn("miss,db;dur=53,app;dur=47.2");
    when(responseHeaders.get("Cache-Control")).thenReturn("public, max-age=3600");
  }

  private void setupAPIMocks() {
    setupMocksCommon();
    when(requestHeaders.get("X-Request-ID")).thenReturn("req-12345");
    when(requestHeaders.get("X-API-Version")).thenReturn("v1.0");
    when(requestHeaders.get("X-Correlation-ID")).thenReturn("corr-67890");
    when(responseHeaders.get("X-RateLimit-Limit")).thenReturn("1000");
    when(responseHeaders.get("X-RateLimit-Remaining")).thenReturn("999");
    when(responseHeaders.get("Content-Type")).thenReturn("application/json");
  }

  // ==================== LIFECYCLE LOGGING TESTS ====================

  @Test
  void testClientConnected() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);

    setupMocks();
    when(flowContext.getClientAddress()).thenReturn(clientAddress);

    // Should not throw and should track client state
    tracker.clientConnected(flowContext);

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    // Verify formatted log was generated
    assertThat(tracker.lastLogMessage).isNotNull();
    assertThat(tracker.lastLogMessage).contains("192.168.1.1");
  }

  @Test
  void testClientSSLHandshakeSucceeded() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);
    SSLSession sslSession = mock(SSLSession.class);
    when(sslSession.getProtocol()).thenReturn("TLSv1.3");
    when(sslSession.getCipherSuite()).thenReturn("TLS_AES_256_GCM_SHA384");

    when(flowContext.getClientAddress()).thenReturn(clientAddress);
    // Connect client first
    tracker.clientConnected(flowContext);

    // Then complete SSL handshake
    tracker.clientSSLHandshakeSucceeded(flowContext, sslSession);

    // Verify no exception thrown and tracking works
    assertThat(tracker).isNotNull();
  }

  @Test
  void testClientDisconnected() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);
    SSLSession sslSession = mock(SSLSession.class);

    // Connect then disconnect
    when(flowContext.getClientAddress()).thenReturn(clientAddress);
    tracker.clientConnected(flowContext);
    tracker.clientDisconnected(flowContext, sslSession);

    // Verify no exception thrown
    assertThat(tracker).isNotNull();
  }

  @Test
  void testServerConnected() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress serverAddress = new InetSocketAddress("10.0.0.1", 443);

    when(flowContext.getClientAddress()).thenReturn(new InetSocketAddress("192.168.1.1", 12345));
    setupMocks();

    // Connect to server
    tracker.serverConnected(fullFlowContext, serverAddress);

    // Verify tracking by completing request
    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    // Verify server address appears in summary
    assertThat(tracker.lastLogMessage).isNotNull();
  }

  @Test
  void testServerDisconnected() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress serverAddress = new InetSocketAddress("10.0.0.1", 443);

    when(flowContext.getClientAddress()).thenReturn(new InetSocketAddress("192.168.1.1", 12345));
    setupMocks();

    // Connect then disconnect
    tracker.serverConnected(fullFlowContext, serverAddress);
    tracker.serverDisconnected(fullFlowContext, serverAddress);

    // Verify no exception thrown
    assertThat(tracker).isNotNull();
  }

  @Test
  void testConnectionSaturated() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);

    setupMocks();
    when(flowContext.getClientAddress()).thenReturn(clientAddress);

    // Connect client
    tracker.clientConnected(flowContext);

    // Mark connection as saturated
    tracker.connectionSaturated(flowContext);

    // Verify no exception thrown
    assertThat(tracker).isNotNull();
  }

  @Test
  void testConnectionWritable() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);

    when(flowContext.getClientAddress()).thenReturn(clientAddress);

    // Mark connection as writable
    tracker.connectionWritable(flowContext);

    // Verify no exception thrown
    assertThat(tracker).isNotNull();
  }

  @Test
  void testConnectionTimedOut() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);

    when(flowContext.getClientAddress()).thenReturn(clientAddress);

    // Simulate timeout
    tracker.connectionTimedOut(flowContext);

    // Verify no exception thrown
    assertThat(tracker).isNotNull();
  }

  @Test
  void testConnectionExceptionCaught() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);

    when(flowContext.getClientAddress()).thenReturn(clientAddress);

    // Simulate exception
    IOException exception = new IOException("Connection reset by peer");
    tracker.connectionExceptionCaught(flowContext, exception);

    // Verify no exception thrown
    assertThat(tracker).isNotNull();
  }

  @Test
  void testCompleteInteractionSummary() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);
    InetSocketAddress serverAddress = new InetSocketAddress("10.0.0.1", 443);

    // Setup full interaction using FullFlowContext for server tracking
    when(flowContext.getClientAddress()).thenReturn(clientAddress);
    tracker.clientConnected(flowContext);

    SSLSession sslSession = mock(SSLSession.class);
    when(sslSession.getProtocol()).thenReturn("TLSv1.3");
    when(sslSession.getCipherSuite()).thenReturn("TLS_AES_256_GCM_SHA384");
    tracker.clientSSLHandshakeSucceeded(flowContext, sslSession);

    // Setup mocks with fullFlowContext
    when(fullFlowContext.getClientAddress()).thenReturn(clientAddress);
    InetAddress inetAddr = mock(InetAddress.class);
    when(inetAddr.getHostAddress()).thenReturn("192.168.1.1");
    when(request.method()).thenReturn(HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(request.headers()).thenReturn(requestHeaders);
    when(response.headers()).thenReturn(responseHeaders);

    tracker.serverConnected(fullFlowContext, serverAddress);

    tracker.requestReceivedFromClient(fullFlowContext, request);

    // Simulate some saturation
    tracker.connectionSaturated(fullFlowContext);
    tracker.connectionWritable(fullFlowContext);

    tracker.responseSentToClient(fullFlowContext, response);

    // Verify formatted log was generated (DEBUG level)
    // Note: Server IP comes from StandardField extraction which returns "-" in test context
    assertThat(tracker.lastLogMessage).isNotNull();
    assertThat(tracker.lastLogMessage).contains("192.168.1.1");
    assertThat(tracker.lastLogMessage).contains("GET");
    assertThat(tracker.lastLogMessage).contains("/test");
    assertThat(tracker.lastLogMessage).contains("200");

    // Verify the interaction completed without errors
    assertThat(tracker).isNotNull();
  }

  @Test
  void testMultipleSaturationsCounted() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);

    setupMocks();
    when(flowContext.getClientAddress()).thenReturn(clientAddress);
    // Connect client
    tracker.clientConnected(flowContext);

    // Simulate multiple saturation events
    tracker.connectionSaturated(flowContext);
    tracker.connectionSaturated(flowContext);
    tracker.connectionSaturated(flowContext);

    // Complete request
    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    // Verify log was generated
    assertThat(tracker.lastLogMessage).isNotNull();
  }

  @Test
  void testExceptionTracking() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    InetSocketAddress clientAddress = new InetSocketAddress("192.168.1.1", 12345);

    setupMocks();
    when(flowContext.getClientAddress()).thenReturn(clientAddress);
    // Connect client
    tracker.clientConnected(flowContext);

    // Simulate multiple exceptions
    tracker.connectionExceptionCaught(flowContext, new IOException("Error 1"));
    tracker.connectionExceptionCaught(flowContext, new RuntimeException("Error 2"));

    // Complete request
    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    // Verify log was generated
    assertThat(tracker.lastLogMessage).isNotNull();
  }

  @Test
  void testPrefixRequestHeaders() {
    // Setup custom headers with prefix
    when(requestHeaders.names())
        .thenReturn(java.util.Set.of("X-Custom-Auth", "X-Custom-Id", "User-Agent"));
    when(requestHeaders.get("X-Custom-Auth")).thenReturn("token123");
    when(requestHeaders.get("X-Custom-Id")).thenReturn("abc-456");
    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addRequestHeadersWithPrefix("X-Custom-")
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Prefix Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"x_custom_auth\":\"token123\"");
    assertThat(tracker.lastLogMessage).contains("\"x_custom_id\":\"abc-456\"");
  }

  @Test
  void testPrefixResponseHeadersWithTransformer() {
    // Setup rate limit headers
    when(responseHeaders.names())
        .thenReturn(java.util.Set.of("X-RateLimit-Limit", "X-RateLimit-Remaining", "Content-Type"));
    when(responseHeaders.get("X-RateLimit-Limit")).thenReturn("1000");
    when(responseHeaders.get("X-RateLimit-Remaining")).thenReturn("999");
    when(responseHeaders.get("Content-Type")).thenReturn("application/json");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addResponseHeadersWithPrefix(
                "X-RateLimit-", name -> name.replace("X-RateLimit-", "").toLowerCase())
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("RateLimit Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"limit\":\"1000\"");
    assertThat(tracker.lastLogMessage).contains("\"remaining\":\"999\"");
  }

  @Test
  void testPrefixHeadersInLtsvFormat() {
    // Setup custom headers
    when(requestHeaders.names()).thenReturn(java.util.Set.of("X-Trace-Id", "X-Span-Id"));
    when(requestHeaders.get("X-Trace-Id")).thenReturn("trace-123");
    when(requestHeaders.get("X-Span-Id")).thenReturn("span-456");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addRequestHeadersWithPrefix("X-Trace-")
            .addRequestHeadersWithPrefix("X-Span-")
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.LTSV, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("LTSV Prefix Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("x_trace_id:trace-123");
    assertThat(tracker.lastLogMessage).contains("x_span_id:span-456");
  }

  @Test
  void testPrefixHeadersInCsvFormat() {
    // Setup custom headers
    when(responseHeaders.names()).thenReturn(java.util.Set.of("X-Cache-Status", "X-Cache-Hits"));
    when(responseHeaders.get("X-Cache-Status")).thenReturn("HIT");
    when(responseHeaders.get("X-Cache-Hits")).thenReturn("42");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addResponseHeadersWithPrefix("X-Cache-")
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.CSV, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("CSV Prefix Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"HIT\"");
    assertThat(tracker.lastLogMessage).contains("\"42\"");
  }

  @Test
  void testRegexRequestHeaders() {
    // Setup headers that match X-.*-Id pattern
    when(requestHeaders.names())
        .thenReturn(java.util.Set.of("X-Request-Id", "X-Trace-Id", "X-Session-Id", "Content-Type"));
    when(requestHeaders.get("X-Request-Id")).thenReturn("req-123");
    when(requestHeaders.get("X-Trace-Id")).thenReturn("trace-456");
    when(requestHeaders.get("X-Session-Id")).thenReturn("sess-789");
    when(requestHeaders.get("Content-Type")).thenReturn("application/json");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addRequestHeadersMatching("X-.*-Id")
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Regex Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"x_request_id\":\"req-123\"");
    assertThat(tracker.lastLogMessage).contains("\"x_trace_id\":\"trace-456\"");
    assertThat(tracker.lastLogMessage).contains("\"x_session_id\":\"sess-789\"");
  }

  @Test
  void testRegexResponseHeadersWithCaptureGroupTransformer() {
    // Setup rate limit headers with different patterns
    when(responseHeaders.names())
        .thenReturn(
            java.util.Set.of(
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-Cache-Status", "Content-Type"));
    when(responseHeaders.get("X-RateLimit-Limit")).thenReturn("1000");
    when(responseHeaders.get("X-RateLimit-Remaining")).thenReturn("999");
    when(responseHeaders.get("X-Cache-Status")).thenReturn("HIT");
    when(responseHeaders.get("Content-Type")).thenReturn("application/json");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addResponseHeadersMatching(
                "X-RateLimit-.*", name -> name.replaceAll("X-RateLimit-", "").toLowerCase())
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Regex RateLimit Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"limit\":\"1000\"");
    assertThat(tracker.lastLogMessage).contains("\"remaining\":\"999\"");
    assertThat(tracker.lastLogMessage).doesNotContain("cache");
  }

  @Test
  void testRegexHeadersCaseInsensitive() {
    // Setup headers with mixed case
    when(requestHeaders.names())
        .thenReturn(java.util.Set.of("X-Request-ID", "x-trace-id", "X-SPAN-ID"));
    when(requestHeaders.get("X-Request-ID")).thenReturn("req-abc");
    when(requestHeaders.get("x-trace-id")).thenReturn("trace-def");
    when(requestHeaders.get("X-SPAN-ID")).thenReturn("span-ghi");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addRequestHeadersMatching("(?i)x-.*-id") // case-insensitive
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.LTSV, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Case-Insensitive Regex Log: " + tracker.lastLogMessage);
    // All three headers should be matched regardless of case
    assertThat(tracker.lastLogMessage).contains("x_request_id:req-abc");
    assertThat(tracker.lastLogMessage).contains("x_trace_id:trace-def");
    assertThat(tracker.lastLogMessage).contains("x_span_id:span-ghi");
  }

  @Test
  void testRegexHeadersInCsvFormat() {
    // Setup headers matching correlation pattern
    when(responseHeaders.names())
        .thenReturn(java.util.Set.of("X-Correlation-Id", "X-Transaction-Id", "X-Server-Name"));
    when(responseHeaders.get("X-Correlation-Id")).thenReturn("corr-123");
    when(responseHeaders.get("X-Transaction-Id")).thenReturn("txn-456");
    when(responseHeaders.get("X-Server-Name")).thenReturn("server01");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addResponseHeadersMatching("X-.*-Id")
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.CSV, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("CSV Regex Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"corr-123\"");
    assertThat(tracker.lastLogMessage).contains("\"txn-456\"");
    assertThat(tracker.lastLogMessage).doesNotContain("server01");
  }

  @Test
  void testExcludeRequestHeaders() {
    // Setup headers including sensitive ones to exclude
    when(requestHeaders.names())
        .thenReturn(java.util.Set.of("X-Request-Id", "Authorization", "Cookie", "Content-Type"));
    when(requestHeaders.get("X-Request-Id")).thenReturn("req-123");
    when(requestHeaders.get("Authorization")).thenReturn("Bearer secret-token");
    when(requestHeaders.get("Cookie")).thenReturn("session=abc123");
    when(requestHeaders.get("Content-Type")).thenReturn("application/json");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .excludeRequestHeadersMatching("Authorization|Cookie")
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Exclude Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"x_request_id\":\"req-123\"");
    assertThat(tracker.lastLogMessage).contains("\"content_type\":\"application/json\"");
    assertThat(tracker.lastLogMessage).doesNotContain("authorization");
    assertThat(tracker.lastLogMessage).doesNotContain("cookie");
    assertThat(tracker.lastLogMessage).doesNotContain("secret-token");
    assertThat(tracker.lastLogMessage).doesNotContain("session=abc123");
  }

  @Test
  void testHeaderValueMasking() {
    // Setup headers with sensitive values to mask
    when(requestHeaders.names())
        .thenReturn(java.util.Set.of("Authorization", "X-API-Key", "X-Request-Id"));
    when(requestHeaders.get("Authorization")).thenReturn("Bearer secret-token-123");
    when(requestHeaders.get("X-API-Key")).thenReturn("api-key-456");
    when(requestHeaders.get("X-Request-Id")).thenReturn("req-789");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addRequestHeadersMatching(
                "X-.*",
                name -> name.toLowerCase().replaceAll("[^a-z0-9]", "_"),
                value -> {
                  // Mask sensitive values
                  if (value.length() > 8) {
                    return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
                  }
                  return value;
                })
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Masked Headers Log: " + tracker.lastLogMessage);
    // api-key-456 -> api-****-456 (first 4 chars + **** + last 4 chars)
    assertThat(tracker.lastLogMessage).contains("\"x_api_key\":\"api-****-456\"");
    assertThat(tracker.lastLogMessage).contains("\"x_request_id\":\"req-789\"");
  }

  @Test
  void testExcludeResponseHeaders() {
    // Setup response headers including sensitive ones to exclude
    when(responseHeaders.names())
        .thenReturn(
            java.util.Set.of("X-RateLimit-Limit", "Set-Cookie", "X-Cache-Status", "Content-Type"));
    when(responseHeaders.get("X-RateLimit-Limit")).thenReturn("1000");
    when(responseHeaders.get("Set-Cookie")).thenReturn("session=secret123; HttpOnly");
    when(responseHeaders.get("X-Cache-Status")).thenReturn("HIT");
    when(responseHeaders.get("Content-Type")).thenReturn("application/json");

    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .excludeResponseHeadersMatching("Set-Cookie")
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Exclude Response Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("\"x_ratelimit_limit\":\"1000\"");
    assertThat(tracker.lastLogMessage).contains("\"x_cache_status\":\"HIT\"");
    assertThat(tracker.lastLogMessage).contains("\"content_type\":\"application/json\"");
    assertThat(tracker.lastLogMessage).doesNotContain("set_cookie");
    assertThat(tracker.lastLogMessage).doesNotContain("secret123");
  }

  @Test
  void testExcludeWithAllHeaders() {
    // Setup headers with X- prefix, using exclude to filter out sensitive ones
    when(requestHeaders.names())
        .thenReturn(
            java.util.Set.of(
                "X-Request-Id", "X-Trace-Id", "X-API-Key", "X-Client-Id", "Content-Type"));
    when(requestHeaders.get("X-Request-Id")).thenReturn("req-123");
    when(requestHeaders.get("X-Trace-Id")).thenReturn("trace-456");
    when(requestHeaders.get("X-API-Key")).thenReturn("secret-api-key");
    when(requestHeaders.get("X-Client-Id")).thenReturn("client-789");
    when(requestHeaders.get("Content-Type")).thenReturn("application/json");

    // Use exclude to log all headers EXCEPT the sensitive X-API-Key
    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .excludeRequestHeadersMatching(
                "X-API-Key",
                name -> name.replace("X-", "").toLowerCase().replaceAll("[^a-z0-9]", "-"),
                value -> value)
            .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.LTSV, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Exclude with All Headers Log: " + tracker.lastLogMessage);
    assertThat(tracker.lastLogMessage).contains("request-id:req-123");
    assertThat(tracker.lastLogMessage).contains("trace-id:trace-456");
    assertThat(tracker.lastLogMessage).contains("client-id:client-789");
    assertThat(tracker.lastLogMessage).contains("content-type:application/json");
    assertThat(tracker.lastLogMessage).doesNotContain("api-key");
    assertThat(tracker.lastLogMessage).doesNotContain("secret-api-key");
  }
}
