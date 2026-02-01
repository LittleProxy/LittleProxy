package org.littleshoot.proxy.extras;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
    assertTrue(tracker.lastLogMessage.contains("127.0.0.1 - - ["));
    assertTrue(tracker.lastLogMessage.contains("] \"GET /test HTTP/1.1\" 200 100"));
  }

  @Test
  void testJsonFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("JSON Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.startsWith("{"));
    assertTrue(tracker.lastLogMessage.contains("\"client_ip\":\"127.0.0.1\""));
    assertTrue(tracker.lastLogMessage.contains("\"method\":\"GET\""));
    assertTrue(tracker.lastLogMessage.contains("\"uri\":\"/test\""));
    assertTrue(tracker.lastLogMessage.contains("\"status\":\"200\""));
    assertTrue(tracker.lastLogMessage.contains("\"bytes\":\"100\""));
  }

  @Test
  void testCustomConfiguration() {
    // Test custom field configuration
    LogFieldConfiguration config = LogFieldConfiguration.builder()
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
    assertTrue(tracker.lastLogMessage.contains("\"request_id\""));
    assertTrue(tracker.lastLogMessage.contains("\"response_time\""));
    assertTrue(tracker.lastLogMessage.contains("\"cache_control\""));
    assertTrue(tracker.lastLogMessage.contains("\"geolocation_country\""));
  }

  @Test
  void testSecurityMonitoringConfiguration() {
    LogFieldConfiguration config = SecurityMonitoringConfig.create();
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    
    setupSecurityMocks();
    
    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Security Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.contains("\"csp\""));
    assertTrue(tracker.lastLogMessage.contains("\"hsts\""));
    assertTrue(tracker.lastLogMessage.contains("\"xss_protection\""));
    assertTrue(tracker.lastLogMessage.contains("\"forwarded_for\""));
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
    assertTrue(tracker.lastLogMessage.contains("\"cache_status\":\"HIT\""), "Should contain cache_status");
    assertTrue(tracker.lastLogMessage.contains("\"server_timing\":\"miss,db;dur=53,app;dur=47.2\""), "Should contain server_timing");
  }

  @Test
  void testAPIManagementConfiguration() {
    LogFieldConfiguration config = APIManagementConfig.create();
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.LTSV, config);
    
    setupAPIMocks();
    
    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("API Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.contains("request_id"));
    assertTrue(tracker.lastLogMessage.contains("rate_limit"));
    assertTrue(tracker.lastLogMessage.contains("correlation_id"));
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
    assertTrue(tracker.lastLogMessage.startsWith("127.0.0.1 - - ["));
    assertTrue(
        tracker.lastLogMessage.contains(
            "] \"GET /test HTTP/1.1\" 200 100 \"http://referrer.com\" \"Mozilla/5.0\""));
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
    assertTrue(tracker.lastLogMessage.contains(" 127.0.0.1 GET /test 200 100 \"Mozilla/5.0\""));
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
    assertTrue(tracker.lastLogMessage.contains("client_ip:127.0.0.1"), "Should contain client_ip");
    assertTrue(tracker.lastLogMessage.contains("method:GET"), "Should contain method");
    assertTrue(tracker.lastLogMessage.contains("uri:/test"), "Should contain uri");
    assertTrue(tracker.lastLogMessage.contains("status:200"), "Should contain status");
    assertTrue(tracker.lastLogMessage.contains("bytes:100"), "Should contain bytes");
    assertTrue(tracker.lastLogMessage.contains("duration:"), "Should contain duration");
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
    assertTrue(tracker.lastLogMessage.contains("\"127.0.0.1\""), "Should contain client IP");
    assertTrue(tracker.lastLogMessage.contains("\"GET\""), "Should contain method");
    assertTrue(tracker.lastLogMessage.contains("\"/test\""), "Should contain URI");
    assertTrue(tracker.lastLogMessage.contains("\"200\""), "Should contain status");
    assertTrue(tracker.lastLogMessage.contains("\"100\""), "Should contain bytes");
    assertTrue(tracker.lastLogMessage.contains("\"Mozilla/5.0\""), "Should contain user agent");
  }

  @Test
  void testHaproxyFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.HAPROXY);
    setupMocksWithDelay();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("HAProxy Log: " + tracker.lastLogMessage);
    // 127.0.0.1 [date] "GET /test HTTP/1.1" 200 100 duration
    assertTrue(tracker.lastLogMessage.startsWith("127.0.0.1 ["));
    assertTrue(tracker.lastLogMessage.contains("] \"GET /test HTTP/1.1\" 200 100 "));
  }

  @Test
  void testSquidFormat() {
    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.SQUID);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Squid Log: " + tracker.lastLogMessage);
    // time elapsed remotehost code/status bytes method URL rfc931
    // peerstatus/peerhost type
    // Check that elapsed time is present (we can't check exact value easily but
    // check structure)
    // 1234567890.123 0 127.0.0.1 ...
    // We now expect something >= 0, not necessarily hardcoded 0.
    // Regex: timestamp space duration space ip ...
    assertTrue(
        tracker.lastLogMessage.matches(
            ".*\\d+ \\d+ 127\\.0\\.0\\.1 TCP_MISS/200 100 GET /test - DIRECT/- -.*"));
  }

  private static class TestableActivityLogger extends ActivityLogger {
    String lastLogMessage;

    public TestableActivityLogger(LogFormat logFormat) {
      super(logFormat,null);
    }

    public TestableActivityLogger(LogFormat logFormat, LogFieldConfiguration config) {
      super(logFormat, config);
    }

    @Override
    protected void log(String message) {
      this.lastLogMessage = message;
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

  @Test
  void testPrefixRequestHeaders() {
    // Setup custom headers with prefix
    when(requestHeaders.names()).thenReturn(java.util.Set.of("X-Custom-Auth", "X-Custom-Id", "User-Agent"));
    when(requestHeaders.get("X-Custom-Auth")).thenReturn("token123");
    when(requestHeaders.get("X-Custom-Id")).thenReturn("abc-456");
    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");

    LogFieldConfiguration config = LogFieldConfiguration.builder()
        .addStandardField(StandardField.CLIENT_IP)
        .addRequestHeadersWithPrefix("X-Custom-")
        .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Prefix Headers Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.contains("\"x_custom_auth\":\"token123\""), "Should contain X-Custom-Auth header");
    assertTrue(tracker.lastLogMessage.contains("\"x_custom_id\":\"abc-456\""), "Should contain X-Custom-Id header");
  }

  @Test
  void testPrefixResponseHeadersWithTransformer() {
    // Setup rate limit headers
    when(responseHeaders.names()).thenReturn(java.util.Set.of("X-RateLimit-Limit", "X-RateLimit-Remaining", "Content-Type"));
    when(responseHeaders.get("X-RateLimit-Limit")).thenReturn("1000");
    when(responseHeaders.get("X-RateLimit-Remaining")).thenReturn("999");
    when(responseHeaders.get("Content-Type")).thenReturn("application/json");

    LogFieldConfiguration config = LogFieldConfiguration.builder()
        .addStandardField(StandardField.CLIENT_IP)
        .addResponseHeadersWithPrefix("X-RateLimit-", name -> name.replace("X-RateLimit-", "").toLowerCase())
        .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("RateLimit Headers Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.contains("\"limit\":\"1000\""), "Should contain limit field");
    assertTrue(tracker.lastLogMessage.contains("\"remaining\":\"999\""), "Should contain remaining field");
  }

  @Test
  void testPrefixHeadersInLtsvFormat() {
    // Setup custom headers
    when(requestHeaders.names()).thenReturn(java.util.Set.of("X-Trace-Id", "X-Span-Id"));
    when(requestHeaders.get("X-Trace-Id")).thenReturn("trace-123");
    when(requestHeaders.get("X-Span-Id")).thenReturn("span-456");

    LogFieldConfiguration config = LogFieldConfiguration.builder()
        .addStandardField(StandardField.CLIENT_IP)
        .addRequestHeadersWithPrefix("X-Trace-")
        .addRequestHeadersWithPrefix("X-Span-")
        .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.LTSV, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("LTSV Prefix Headers Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.contains("x_trace_id:trace-123"), "Should contain trace_id");
    assertTrue(tracker.lastLogMessage.contains("x_span_id:span-456"), "Should contain span_id");
  }

  @Test
  void testPrefixHeadersInCsvFormat() {
    // Setup custom headers
    when(responseHeaders.names()).thenReturn(java.util.Set.of("X-Cache-Status", "X-Cache-Hits"));
    when(responseHeaders.get("X-Cache-Status")).thenReturn("HIT");
    when(responseHeaders.get("X-Cache-Hits")).thenReturn("42");

    LogFieldConfiguration config = LogFieldConfiguration.builder()
        .addStandardField(StandardField.CLIENT_IP)
        .addResponseHeadersWithPrefix("X-Cache-")
        .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.CSV, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("CSV Prefix Headers Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.contains("\"HIT\""), "Should contain cache status");
    assertTrue(tracker.lastLogMessage.contains("\"42\""), "Should contain cache hits");
  }

  @Test
  void testRegexRequestHeaders() {
    // Setup headers that match X-.*-Id pattern
    when(requestHeaders.names()).thenReturn(java.util.Set.of("X-Request-Id", "X-Trace-Id", "X-Session-Id", "Content-Type"));
    when(requestHeaders.get("X-Request-Id")).thenReturn("req-123");
    when(requestHeaders.get("X-Trace-Id")).thenReturn("trace-456");
    when(requestHeaders.get("X-Session-Id")).thenReturn("sess-789");
    when(requestHeaders.get("Content-Type")).thenReturn("application/json");

    LogFieldConfiguration config = LogFieldConfiguration.builder()
        .addStandardField(StandardField.CLIENT_IP)
        .addRequestHeadersMatching("X-.*-Id")
        .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Regex Headers Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.contains("\"x_request_id\":\"req-123\""), "Should contain X-Request-Id header");
    assertTrue(tracker.lastLogMessage.contains("\"x_trace_id\":\"trace-456\""), "Should contain X-Trace-Id header");
    assertTrue(tracker.lastLogMessage.contains("\"x_session_id\":\"sess-789\""), "Should contain X-Session-Id header");
  }

  @Test
  void testRegexResponseHeadersWithCaptureGroupTransformer() {
    // Setup rate limit headers with different patterns
    when(responseHeaders.names()).thenReturn(java.util.Set.of("X-RateLimit-Limit", "X-RateLimit-Remaining", "X-Cache-Status", "Content-Type"));
    when(responseHeaders.get("X-RateLimit-Limit")).thenReturn("1000");
    when(responseHeaders.get("X-RateLimit-Remaining")).thenReturn("999");
    when(responseHeaders.get("X-Cache-Status")).thenReturn("HIT");
    when(responseHeaders.get("Content-Type")).thenReturn("application/json");

    LogFieldConfiguration config = LogFieldConfiguration.builder()
        .addStandardField(StandardField.CLIENT_IP)
        .addResponseHeadersMatching("X-RateLimit-.*", name -> name.replaceAll("X-RateLimit-", "").toLowerCase())
        .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.JSON, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Regex RateLimit Headers Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.contains("\"limit\":\"1000\""), "Should contain limit field");
    assertTrue(tracker.lastLogMessage.contains("\"remaining\":\"999\""), "Should contain remaining field");
    assertTrue(!tracker.lastLogMessage.contains("cache"), "Should not contain cache header");
  }

  @Test
  void testRegexHeadersCaseInsensitive() {
    // Setup headers with mixed case
    when(requestHeaders.names()).thenReturn(java.util.Set.of("X-Request-ID", "x-trace-id", "X-SPAN-ID"));
    when(requestHeaders.get("X-Request-ID")).thenReturn("req-abc");
    when(requestHeaders.get("x-trace-id")).thenReturn("trace-def");
    when(requestHeaders.get("X-SPAN-ID")).thenReturn("span-ghi");

    LogFieldConfiguration config = LogFieldConfiguration.builder()
        .addStandardField(StandardField.CLIENT_IP)
        .addRequestHeadersMatching("(?i)x-.*-id")  // case-insensitive
        .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.LTSV, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("Case-Insensitive Regex Log: " + tracker.lastLogMessage);
    // All three headers should be matched regardless of case
    assertTrue(tracker.lastLogMessage.contains("x_request_id:req-abc") || tracker.lastLogMessage.contains("x_request_id:req-abc"), "Should contain request id");
    assertTrue(tracker.lastLogMessage.contains("x_trace_id:trace-def") || tracker.lastLogMessage.contains("x_trace_id:trace-def"), "Should contain trace id");
    assertTrue(tracker.lastLogMessage.contains("x_span_id:span-ghi") || tracker.lastLogMessage.contains("x_span_id:span-ghi"), "Should contain span id");
  }

  @Test
  void testRegexHeadersInCsvFormat() {
    // Setup headers matching correlation pattern
    when(responseHeaders.names()).thenReturn(java.util.Set.of("X-Correlation-Id", "X-Transaction-Id", "X-Server-Name"));
    when(responseHeaders.get("X-Correlation-Id")).thenReturn("corr-123");
    when(responseHeaders.get("X-Transaction-Id")).thenReturn("txn-456");
    when(responseHeaders.get("X-Server-Name")).thenReturn("server01");

    LogFieldConfiguration config = LogFieldConfiguration.builder()
        .addStandardField(StandardField.CLIENT_IP)
        .addResponseHeadersMatching("X-.*-Id")
        .build();

    TestableActivityLogger tracker = new TestableActivityLogger(LogFormat.CSV, config);
    setupMocks();

    tracker.requestReceivedFromClient(flowContext, request);
    tracker.responseSentToClient(flowContext, response);

    System.out.println("CSV Regex Headers Log: " + tracker.lastLogMessage);
    assertTrue(tracker.lastLogMessage.contains("\"corr-123\""), "Should contain correlation id");
    assertTrue(tracker.lastLogMessage.contains("\"txn-456\""), "Should contain transaction id");
    assertTrue(!tracker.lastLogMessage.contains("server01"), "Should not contain server name (doesn't match pattern)");
  }
}
