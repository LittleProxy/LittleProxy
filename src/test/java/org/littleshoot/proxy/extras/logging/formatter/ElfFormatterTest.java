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

class ElfFormatterTest {

  private ElfFormatter formatter;
  private FlowContext flowContext;
  private HttpRequest request;
  private HttpResponse response;
  private HttpHeaders requestHeaders;
  private HttpHeaders responseHeaders;

  @BeforeEach
  void setUp() {
    formatter = new ElfFormatter();
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
  void testFormatBasicElf() {
    LogFieldConfiguration config = LogFieldConfiguration.builder().build();

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/apache_pb.gif");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_0);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("2326");
    when(requestHeaders.get("Referer")).thenReturn("http://www.example.com/start.html");
    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/4.08 [en] (Win98; I ;Nav)");

    String result =
        formatter.format(flowContext, request, response, ZonedDateTime.now(), "flow-id", config);

    // ELF format: host ident authuser [date] "request" status bytes "referer" "user-agent"
    assertThat(result).startsWith("127.0.0.1");
    assertThat(result).contains(" - - "); // ident and authuser
    assertThat(result).contains(" ["); // date start
    assertThat(result).contains("] "); // date end
    assertThat(result).contains("\"GET /apache_pb.gif HTTP/1.0\"");
    assertThat(result).contains(" 200 ");
    assertThat(result).contains(" 2326 ");
    assertThat(result).contains("\"http://www.example.com/start.html\"");
    assertThat(result).contains("\"Mozilla/4.08 [en] (Win98; I ;Nav)\"");
  }

  @Test
  void testFormatWithNullClientAddress() {
    when(flowContext.getClientAddress()).thenReturn(null);

    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(requestHeaders.get("Referer")).thenReturn("-");
    when(requestHeaders.get("User-Agent")).thenReturn("TestAgent");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).startsWith("-");
  }

  @Test
  void testFormatWithMissingReferer() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(requestHeaders.get("Referer")).thenReturn(null);
    when(requestHeaders.get("User-Agent")).thenReturn("TestAgent");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains("\"-\"");
  }

  @Test
  void testFormatWithMissingUserAgent() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("/test");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(requestHeaders.get("Referer")).thenReturn("http://example.com");
    when(requestHeaders.get("User-Agent")).thenReturn(null);

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).endsWith("\"-\"");
  }

  @Test
  void testFormatWithPostMethod() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.POST);
    when(request.uri()).thenReturn("/api/users");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.CREATED);
    when(responseHeaders.get("Content-Length")).thenReturn("50");
    when(requestHeaders.get("Referer")).thenReturn("-");
    when(requestHeaders.get("User-Agent")).thenReturn("TestAgent");

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
  }

  @Test
  void testFormatWithFullUrl() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(request.uri()).thenReturn("http://example.com/path?query=1");
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);
    when(responseHeaders.get("Content-Length")).thenReturn("100");
    when(requestHeaders.get("Referer")).thenReturn("-");
    when(requestHeaders.get("User-Agent")).thenReturn("TestAgent");

    String result =
        formatter.format(
            flowContext,
            request,
            response,
            ZonedDateTime.now(),
            "flow-id",
            LogFieldConfiguration.builder().build());

    assertThat(result).contains("http://example.com/path?query=1");
  }

  @Test
  void testGetSupportedFormat() {
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.ELF);
  }
}
