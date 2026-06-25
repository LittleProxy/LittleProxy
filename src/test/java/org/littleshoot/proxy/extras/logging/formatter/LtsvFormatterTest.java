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

class LtsvFormatterTest {

  private LtsvFormatter formatter;
  private FlowContext flowContext;
  private HttpRequest request;
  private HttpResponse response;
  private HttpHeaders requestHeaders;
  private HttpHeaders responseHeaders;

  @BeforeEach
  void setUp() {
    formatter = new LtsvFormatter();
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

    assertThat(result).contains("flow_id:test-flow-id");
    assertThat(result).contains("client_ip:127.0.0.1");
    assertThat(result).contains("method:GET");
    assertThat(result).contains("status:200");
  }

  @Test
  void testFormatWithEmptyConfig() {
    LogFieldConfiguration config = LogFieldConfiguration.builder().build();

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).isEqualTo("flow_id:flow-id");
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

    assertThat(result).contains("flow_id:flow-id");
    assertThat(result).contains("http_request_processing_time_ms:42");
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

    // Null timing data shows as "-"
    assertThat(result).isEqualTo("flow_id:flow-id\thttp_request_processing_time_ms:-");
  }

  @Test
  void testGetSupportedFormat() {
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.LTSV);
  }

  @Test
  void testFormatLifecycleEvent() {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("status", 200);
    attributes.put("processing_time_ms", 15);

    String result =
        formatter.formatLifecycleEvent(
            LifecycleEvent.RESPONSE_SENT, flowContext, attributes, "test-flow-id");

    assertThat(result).contains("flow_id:test-flow-id");
    assertThat(result).contains("event:response_sent");
    assertThat(result).contains("client_ip:127.0.0.1");
    assertThat(result).contains("status:200");
    assertThat(result).contains("processing_time_ms:15");
  }

  @Test
  void testFormatWithBytesField() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.BYTES).build();

    when(responseHeaders.get("Content-Length")).thenReturn("1024");

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("flow_id:flow-id");
    assertThat(result).contains("bytes:1024");
  }

  @Test
  void testFormatWithUriField() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.URI).build();

    when(request.uri()).thenReturn("/api/users?id=123");
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("flow_id:flow-id");
    assertThat(result).contains("uri:/api/users?id=123");
  }

  @Test
  void testFormatWithUserAgentField() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.USER_AGENT).build();

    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("flow_id:flow-id");
    assertThat(result).contains("user_agent:Mozilla/5.0");
  }

  @Test
  void testFormatWithNullClientAddress() {
    when(flowContext.getClientAddress()).thenReturn(null);

    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addStandardField(StandardField.CLIENT_IP).build();

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    assertThat(result).contains("flow_id:flow-id");
    assertThat(result).contains("client_ip:-");
  }
}
