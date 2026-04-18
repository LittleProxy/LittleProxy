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
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

class W3cFormatterTest {

  private W3cFormatter formatter;
  private FlowContext flowContext;
  private HttpRequest request;
  private HttpResponse response;
  private HttpHeaders requestHeaders;
  private HttpHeaders responseHeaders;

  @BeforeEach
  void setUp() {
    formatter = new W3cFormatter();
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
  void testFormatBasicW3c() {
    LogFieldConfiguration config = LogFieldConfiguration.builder().build();
    ZonedDateTime now = ZonedDateTime.of(2024, 1, 15, 10, 30, 45, 0, ZoneId.of("UTC"));

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/apache_pb.gif");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_0);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("2326");
    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/4.0");

    String result = formatter.format(flowContext, request, response, now, "flow-id", config);

    // W3C format: date time c-ip cs-method cs-uri-stem sc-status sc-bytes cs(User-Agent)
    assertThat(result).startsWith("2024-01-15 10:30:45");
    assertThat(result).contains(" 127.0.0.1 ");
    assertThat(result).contains(" GET ");
    assertThat(result).contains(" /apache_pb.gif ");
    assertThat(result).contains(" 200 ");
    assertThat(result).contains(" 2326 ");
    assertThat(result).endsWith("\"Mozilla/4.0\"");
  }

  @Test
  void testFormatWithNullClientAddress() {
    when(flowContext.getClientAddress()).thenReturn(null);
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(requestHeaders.get("User-Agent")).thenReturn("TestAgent");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" - ");
  }

  @Test
  void testFormatWithMissingContentLength() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn(null);
    when(requestHeaders.get("User-Agent")).thenReturn("TestAgent");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" - ");
  }

  @Test
  void testFormatWithPostMethod() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.POST);
    when(request.uri()).thenReturn("/api/users");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.CREATED);
    when(responseHeaders.get("Content-Length")).thenReturn("50");
    when(requestHeaders.get("User-Agent")).thenReturn("TestAgent");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" POST ");
    assertThat(result).contains(" 201 ");
  }

  @Test
  void testFormatWithFullUrl() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("http://example.com/path?query=1");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(requestHeaders.get("User-Agent")).thenReturn("TestAgent");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    // W3C format should extract just the path (uri-stem)
    assertThat(result).contains(" /path ");
  }

  @Test
  void testFormatWithMissingUserAgent() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(requestHeaders.get("User-Agent")).thenReturn(null);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).endsWith("\"-\"");
  }

  @Test
  void testGetSupportedFormat() {
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.W3C);
  }

  @Test
  void testFormatWithPathOnly() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/just/path/no/query");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(requestHeaders.get("User-Agent")).thenReturn("Test");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains(" /just/path/no/query ");
  }

  @Test
  void testFormatWithRootPath() {
    ZonedDateTime now = ZonedDateTime.now();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("http://example.com");
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(requestHeaders.get("User-Agent")).thenReturn("Test");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            now,
            "flow-id",
            LogFieldConfiguration.builder().build());

    // URL without path should return "/"
    assertThat(result).contains(" / ");
  }
}
