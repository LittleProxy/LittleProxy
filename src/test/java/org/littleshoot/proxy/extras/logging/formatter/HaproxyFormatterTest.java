package org.littleshoot.proxy.extras.logging.formatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

class HaproxyFormatterTest {

  private HaproxyFormatter formatter;
  private FlowContext flowContext;
  private HttpRequest request;
  private HttpResponse response;
  private HttpHeaders responseHeaders;

  @BeforeEach
  void setUp() {
    formatter = new HaproxyFormatter();
    flowContext = mock(FlowContext.class);
    request = mock(HttpRequest.class);
    response = mock(HttpResponse.class);
    responseHeaders = mock(HttpHeaders.class);

    when(request.headers()).thenReturn(mock(HttpHeaders.class));
    when(response.headers()).thenReturn(responseHeaders);
    when(flowContext.getClientAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
  }

  @Test
  void testFormatBasicHaproxy() {
    LogFieldConfiguration config = LogFieldConfiguration.builder().build();
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/path");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("1234");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(500L);

    String result = formatter.format(flowContext, request, response, now, "flow-id", config);

    // HAProxy format: process[pid]: client_ip:port [accept_date] frontend backend/server
    // Tq/Tw/Tc/Tr/Ta status bytes ...
    assertThat(result).startsWith("littleproxy[0]: ");
    assertThat(result).contains("127.0.0.1:12345");
    assertThat(result).contains("frontend backend/server");
    assertThat(result).contains("0/0/0/0/500"); // Tq/Tw/Tc/Tr/Ta timing
    assertThat(result).contains(" 200 ");
    assertThat(result).contains(" 1234 ");
    assertThat(result).contains("\"GET /path HTTP/1.1\"");
  }

  @Test
  void testFormatWithNullClientAddress() {
    when(flowContext.getClientAddress()).thenReturn(null);

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(0L);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    // client_port should be 0 when no client address
    assertThat(result).contains(":0 ");
  }

  @Test
  void testFormatWithMissingContentLength() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn(null);
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(100L);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" - "); // missing content length shows as "-"
  }

  @Test
  void testFormatWithNullTimingData() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(null);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains("0/0/0/0/-"); // null timing shows as "-"
  }

  @Test
  void testFormatWithPostMethod() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.POST);
    when(request.uri()).thenReturn("/api/users");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.CREATED);
    when(responseHeaders.get("Content-Length")).thenReturn("50");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(200L);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains("\"POST /api/users HTTP/1.1\"");
    assertThat(result).contains(" 201 ");
    assertThat(result).contains("0/0/0/0/200");
  }

  @Test
  void testGetSupportedFormat() {
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.HAPROXY);
  }
}
