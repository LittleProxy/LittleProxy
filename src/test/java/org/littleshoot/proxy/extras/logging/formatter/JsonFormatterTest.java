package org.littleshoot.proxy.extras.logging.formatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;
import org.littleshoot.proxy.extras.logging.StandardField;

class JsonFormatterTest {

  private JsonFormatter formatter;
  private FlowContext flowContext;
  private HttpRequest request;
  private HttpResponse response;
  private HttpHeaders requestHeaders;
  private HttpHeaders responseHeaders;

  @BeforeEach
  void setUp() {
    formatter = new JsonFormatter();
    flowContext = mock(FlowContext.class);
    request = mock(HttpRequest.class);
    response = mock(HttpResponse.class);
    requestHeaders = mock(HttpHeaders.class);
    responseHeaders = mock(HttpHeaders.class);

    when(request.headers()).thenReturn(requestHeaders);
    when(response.headers()).thenReturn(responseHeaders);
    when(flowContext.getClientAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
  }

  @Test
  void testFormatWithBasicFields() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.CLIENT_IP)
            .addStandardField(StandardField.METHOD)
            .addStandardField(StandardField.STATUS)
            .build();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);

    String result =
        formatter.format(
            flowContext, request, response, ZonedDateTime.now(), "test-flow-id", config);

    assertThat(result).startsWith("{");
    assertThat(result).endsWith("}");
    assertThat(result).contains("\"flow_id\":\"test-flow-id\"");
    assertThat(result).contains("\"client_ip\":\"127.0.0.1\"");
    assertThat(result).contains("\"method\":\"GET\"");
    assertThat(result).contains("\"status\":\"200\"");
  }

  @Test
  void testFormatEscapesQuotes() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.URI).build();

    when(request.uri()).thenReturn("/path?query=\"with quotes\"");
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    // Quotes should be escaped - the JSON contains \\" which represents \" in the output
    assertThat(result).contains("\\\\\"with quotes\\\\\"");
  }

  @Test
  void testFormatEscapesBackslashes() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.URI).build();

    when(request.uri()).thenReturn("/path\\with\\backslashes");
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("\\\\with\\\\backslashes");
  }

  @Test
  void testFormatHandlesNullTimingData() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.HTTP_REQUEST_PROCESSING_TIME_MS)
            .build();

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(null);

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    // Null timing data should show as "-"
    assertThat(result).contains("\"http_request_processing_time_ms\":\"-\"");
  }

  @Test
  void testFormatWithTimingData() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .addStandardField(StandardField.HTTP_REQUEST_PROCESSING_TIME_MS)
            .build();

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(42L);

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("\"http_request_processing_time_ms\":\"42\"");
  }

  @Test
  void testGetSupportedFormat() {
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.JSON);
  }

  @Test
  void testFormatLifecycleEvent() {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("status", 200);
    attributes.put("processing_time_ms", 15);

    String result =
        formatter.formatLifecycleEvent(
            LifecycleEvent.RESPONSE_SENT, flowContext, attributes, "test-flow-id");

    assertThat(result).startsWith("{");
    assertThat(result).endsWith("}");
    assertThat(result).contains("\"flow_id\":\"test-flow-id\"");
    assertThat(result).contains("\"event\":\"response_sent\"");
    assertThat(result).contains("\"client_ip\":\"127.0.0.1\"");
    assertThat(result).contains("\"status\":\"200\"");
    assertThat(result).contains("\"processing_time_ms\":\"15\"");
  }

  @Test
  void testFormatWithBytesField() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.BYTES).build();

    when(responseHeaders.get("Content-Length")).thenReturn("1024");

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("\"bytes\":\"1024\"");
  }

  @Test
  void testFormatWithUriField() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.URI).build();

    when(request.uri()).thenReturn("/api/users?id=123");
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("\"uri\":\"/api/users?id=123\"");
  }

  @Test
  void testFormatWithRefererField() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.REFERER).build();

    when(requestHeaders.get("Referer")).thenReturn("http://example.com/page");

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("\"referer\":\"http://example.com/page\"");
  }

  @Test
  void testFormatWithUserAgentField() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.USER_AGENT).build();

    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("\"user_agent\":\"Mozilla/5.0\"");
  }

  @Test
  void testFormatWithEmptyConfig() {
    LogFieldConfiguration config = LogFieldConfiguration.builder().build();

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).isEqualTo("{\"flow_id\":\"flow-id\"}");
  }

  @Test
  void testFormatWithNullClientAddress() {
    when(flowContext.getClientAddress()).thenReturn(null);

    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.CLIENT_IP).build();

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("\"client_ip\":\"-\"");
  }

  @Test
  void testFormatWithProtocolField() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.PROTOCOL).build();

    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("\"protocol\":\"HTTP/1.1\"");
  }
}
