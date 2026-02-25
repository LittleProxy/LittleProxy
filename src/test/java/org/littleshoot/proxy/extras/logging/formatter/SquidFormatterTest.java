package org.littleshoot.proxy.extras.logging.formatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

class SquidFormatterTest {

  private SquidFormatter formatter;
  private FlowContext flowContext;
  private FullFlowContext fullFlowContext;
  private HttpRequest request;
  private HttpResponse response;
  private HttpHeaders responseHeaders;

  @BeforeEach
  void setUp() {
    formatter = new SquidFormatter();
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
  void testFormatBasicSquid() {
    LogFieldConfiguration config = LogFieldConfiguration.builder().build();
    ZonedDateTime now = ZonedDateTime.of(2009, 2, 13, 23, 31, 30, 123000000, ZoneId.of("UTC"));

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("http://example.com/path");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("2326");
    when(responseHeaders.get("Content-Type")).thenReturn("text/html");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(500L);

    String result = formatter.format(flowContext, request, response, now, "flow-id", config);

    // Squid format: time elapsed remotehost code/status bytes method URL rfc931 peerstatus/peerhost
    // type
    assertThat(result).startsWith("1234567890.123 "); // epoch time with millis
    assertThat(result).contains(" 500 "); // elapsed time
    assertThat(result).contains(" 127.0.0.1 ");
    assertThat(result).contains(" TCP_HIT/200 ");
    assertThat(result).contains(" 2326 ");
    assertThat(result).contains(" GET ");
    assertThat(result).contains(" http://example.com/path ");
    assertThat(result).contains(" - "); // rfc931
    assertThat(result).contains(" DIRECT/");
    assertThat(result).endsWith(" text/html");
  }

  @Test
  void testFormatWithMissStatus() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(responseHeaders.get("Content-Type")).thenReturn("text/plain");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(100L);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" TCP_MISS/404 ");
  }

  @Test
  void testFormatWithNullClientAddress() {
    when(flowContext.getClientAddress()).thenReturn(null);
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(responseHeaders.get("Content-Type")).thenReturn("text/html");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(100L);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" - "); // null client shows as "-"
  }

  @Test
  void testFormatWithMissingContentLength() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn(null);
    when(responseHeaders.get("Content-Type")).thenReturn("text/html");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(100L);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" - "); // missing content length shows as "-"
  }

  @Test
  void testFormatWithMissingContentType() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(responseHeaders.get("Content-Type")).thenReturn(null);
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(100L);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).endsWith(" -"); // missing content type shows as "-"
  }

  @Test
  void testFormatWithPostMethod() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.POST);
    when(request.uri()).thenReturn("/api/users");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.CREATED);
    when(responseHeaders.get("Content-Length")).thenReturn("50");
    when(responseHeaders.get("Content-Type")).thenReturn("application/json");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(200L);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" POST ");
    // 201 is still in 2xx range so it's considered a HIT
    assertThat(result).contains(" TCP_HIT/201 ");
  }

  @Test
  void testFormatWithRedirectStatus() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/redirect");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.FOUND);
    when(responseHeaders.get("Content-Length")).thenReturn("0");
    when(responseHeaders.get("Content-Type")).thenReturn("text/html");
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(50L);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" TCP_HIT/302 "); // 302 is still a hit
  }

  @Test
  void testGetSupportedFormat() {
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.SQUID);
  }
}
