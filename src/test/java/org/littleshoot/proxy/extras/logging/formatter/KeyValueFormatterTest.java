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
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

class KeyValueFormatterTest {

  private KeyValueFormatter formatter;
  private FlowContext flowContext;
  private FullFlowContext fullFlowContext;
  private HttpRequest request;
  private HttpResponse response;
  private HttpHeaders responseHeaders;

  @BeforeEach
  void setUp() {
    formatter = new KeyValueFormatter();
    flowContext = mock(FlowContext.class);
    fullFlowContext = mock(FullFlowContext.class);
    request = mock(HttpRequest.class);
    response = mock(HttpResponse.class);
    responseHeaders = mock(HttpHeaders.class);

    when(request.headers()).thenReturn(mock(HttpHeaders.class));
    when(response.headers()).thenReturn(responseHeaders);
    when(flowContext.getClientAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
    when(fullFlowContext.getClientAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
    when(fullFlowContext.getServerHostAndPort()).thenReturn("10.0.0.1:8080");
  }

  @Test
  void testFormatWithBasicFields() {
    LogFieldConfiguration config = LogFieldConfiguration.builder().build();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/api/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("1024");
    when(fullFlowContext.getTimingData("http_request_processing_time_ms")).thenReturn(42L);

    String result =
        formatter.format(
            fullFlowContext, request, response, ZonedDateTime.now(), "test-flow-id", config);

    assertThat(result).contains("flow_id=test-flow-id");
    assertThat(result).contains("client_ip=127.0.0.1");
    assertThat(result).contains("client_port=12345");
    assertThat(result).contains("server_ip=10.0.0.1");
    assertThat(result).contains("server_port=8080");
    assertThat(result).contains("method=GET");
    assertThat(result).contains("uri=\"/api/test\"");
    assertThat(result).contains("protocol=HTTP/1.1");
    assertThat(result).contains("status=200");
    assertThat(result).contains("bytes=1024");
    assertThat(result).contains("http_request_ms=42");
  }

  @Test
  void testFormatWithNullClientAddress() {
    when(flowContext.getClientAddress()).thenReturn(null);
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn(null);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains("client_ip=-");
    assertThat(result).contains("client_port=0");
  }

  @Test
  void testFormatWithNullServerInfo() {
    when(fullFlowContext.getServerHostAndPort()).thenReturn(null);
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");

    String result =
        formatter.format(
            fullFlowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains("server_ip=-");
    assertThat(result).contains("server_port=0");
  }

  @Test
  void testFormatWithServerHostOnly() {
    when(fullFlowContext.getServerHostAndPort()).thenReturn("example.com");
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");

    String result =
        formatter.format(
            fullFlowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains("server_ip=example.com");
    assertThat(result).contains("server_port=0");
  }

  @Test
  void testGetSupportedFormat() {
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.KEYVALUE);
  }

  @Test
  void testFormatLifecycleEvent() {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("status", 200);
    attributes.put("processing_time_ms", 15);

    String result =
        formatter.formatLifecycleEvent(
            LifecycleEvent.RESPONSE_SENT, flowContext, attributes, "test-flow-id");

    assertThat(result).contains("flow_id=test-flow-id");
    assertThat(result).contains("event=response_sent");
    assertThat(result).contains("client_ip=127.0.0.1");
    assertThat(result).contains("status=200");
    assertThat(result).contains("processing_time_ms=15");
  }

  @Test
  void testFormatWithPostMethod() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.POST);
    when(request.uri()).thenReturn("/api/users");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.CREATED);
    when(responseHeaders.get("Content-Length")).thenReturn("50");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains("method=POST");
    assertThat(result).contains("status=201");
  }

  @Test
  void testFormatWithUriContainingSpaces() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/path with spaces");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains("uri=\"/path with spaces\"");
  }
}
