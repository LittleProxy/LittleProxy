package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;

class StandardFieldTest {

  private FlowContext flowContext;
  private HttpRequest request;
  private HttpResponse response;
  private HttpHeaders requestHeaders;
  private HttpHeaders responseHeaders;

  @BeforeEach
  void setUp() {
    flowContext = mock(FlowContext.class);
    request = mock(HttpRequest.class);
    response = mock(HttpResponse.class);
    requestHeaders = mock(HttpHeaders.class);
    responseHeaders = mock(HttpHeaders.class);

    when(request.headers()).thenReturn(requestHeaders);
    when(response.headers()).thenReturn(responseHeaders);
  }

  @Test
  void testAllStandardFieldsHaveName() {
    for (StandardField field : StandardField.values()) {
      assertThat(field.getName()).isNotNull().isNotEmpty();
      assertThat(field.getDescription()).isNotNull().isNotEmpty();
    }
  }

  @Test
  void testTimestampField() {
    String value = StandardField.TIMESTAMP.extractValue(flowContext, request, response);
    assertThat(value).isNotNull();
    assertThat(value).contains("T"); // ISO format has T
    assertThat(value).contains("Z"); // UTC timezone
  }

  @Test
  void testClientIpField() {
    when(flowContext.getClientAddress()).thenReturn(new InetSocketAddress("192.168.1.1", 12345));

    String value = StandardField.CLIENT_IP.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("192.168.1.1");
  }

  @Test
  void testClientIpFieldWhenNull() {
    when(flowContext.getClientAddress()).thenReturn(null);

    String value = StandardField.CLIENT_IP.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testRemoteIpFromXForwardedFor() {
    when(requestHeaders.get("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2, 10.0.0.3");
    when(flowContext.getClientAddress()).thenReturn(new InetSocketAddress("192.168.1.1", 12345));

    String value = StandardField.REMOTE_IP.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("10.0.0.1");
  }

  @Test
  void testRemoteIpFromXRealIp() {
    when(requestHeaders.get("X-Forwarded-For")).thenReturn(null);
    when(requestHeaders.get("X-Real-IP")).thenReturn("10.0.0.5");
    when(flowContext.getClientAddress()).thenReturn(new InetSocketAddress("192.168.1.1", 12345));

    String value = StandardField.REMOTE_IP.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("10.0.0.5");
  }

  @Test
  void testRemoteIpFallbackToClientAddress() {
    when(requestHeaders.get("X-Forwarded-For")).thenReturn(null);
    when(requestHeaders.get("X-Real-IP")).thenReturn(null);
    when(flowContext.getClientAddress()).thenReturn(new InetSocketAddress("192.168.1.1", 12345));

    String value = StandardField.REMOTE_IP.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("192.168.1.1");
  }

  @Test
  void testRemoteIpWhenAllNull() {
    when(requestHeaders.get("X-Forwarded-For")).thenReturn(null);
    when(requestHeaders.get("X-Real-IP")).thenReturn(null);
    when(flowContext.getClientAddress()).thenReturn(null);

    String value = StandardField.REMOTE_IP.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testMethodField() {
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.POST);

    String value = StandardField.METHOD.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("POST");
  }

  @Test
  void testUriFieldWithAbsoluteUrl() {
    when(request.uri()).thenReturn("http://example.com/path?query=1");
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);

    String value = StandardField.URI.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("http://example.com/path?query=1");
  }

  @Test
  void testUriFieldWithRelativePath() {
    when(request.uri()).thenReturn("/path?query=1");
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(requestHeaders.get("Host")).thenReturn("example.com");

    String value = StandardField.URI.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("http://example.com/path?query=1");
  }

  @Test
  void testUriFieldWithConnectRequest() {
    when(request.uri()).thenReturn("example.com:443");
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.CONNECT);

    String value = StandardField.URI.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("example.com:443");
  }

  @Test
  void testUriFieldWithNoHost() {
    when(request.uri()).thenReturn("/path");
    when(request.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.GET);
    when(requestHeaders.get("Host")).thenReturn(null);

    String value = StandardField.URI.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("/path");
  }

  @Test
  void testStatusField() {
    when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.OK);

    String value = StandardField.STATUS.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("200");
  }

  @Test
  void testBytesField() {
    when(responseHeaders.get("Content-Length")).thenReturn("1024");

    String value = StandardField.BYTES.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("1024");
  }

  @Test
  void testBytesFieldWhenNull() {
    when(responseHeaders.get("Content-Length")).thenReturn(null);

    String value = StandardField.BYTES.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testHttpRequestProcessingTimeField() {
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(42L);

    String value =
        StandardField.HTTP_REQUEST_PROCESSING_TIME_MS.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("42");
  }

  @Test
  void testHttpRequestProcessingTimeFieldWhenNull() {
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(null);

    String value =
        StandardField.HTTP_REQUEST_PROCESSING_TIME_MS.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testRefererField() {
    when(requestHeaders.get("Referer")).thenReturn("http://example.com/page");

    String value = StandardField.REFERER.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("http://example.com/page");
  }

  @Test
  void testRefererFieldWhenNull() {
    when(requestHeaders.get("Referer")).thenReturn(null);

    String value = StandardField.REFERER.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testUserAgentField() {
    when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");

    String value = StandardField.USER_AGENT.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("Mozilla/5.0");
  }

  @Test
  void testUserAgentFieldWhenNull() {
    when(requestHeaders.get("User-Agent")).thenReturn(null);

    String value = StandardField.USER_AGENT.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testProtocolField() {
    when(request.protocolVersion()).thenReturn(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);

    String value = StandardField.PROTOCOL.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("HTTP/1.1");
  }

  @Test
  void testTcpConnectionEstablishmentTimeField() {
    when(flowContext.getTimingData("tcp_connection_establishment_time_ms")).thenReturn(100L);

    String value =
        StandardField.TCP_CONNECTION_ESTABLISHMENT_TIME_MS.extractValue(
            flowContext, request, response);

    assertThat(value).isEqualTo("100");
  }

  @Test
  void testTcpConnectionEstablishmentTimeFieldWhenNull() {
    when(flowContext.getTimingData("tcp_connection_establishment_time_ms")).thenReturn(null);

    String value =
        StandardField.TCP_CONNECTION_ESTABLISHMENT_TIME_MS.extractValue(
            flowContext, request, response);

    assertThat(value).isNull();
  }

  @Test
  void testTcpClientConnectionDurationField() {
    when(flowContext.getTimingData("tcp_client_connection_duration_ms")).thenReturn(5000L);

    String value =
        StandardField.TCP_CLIENT_CONNECTION_DURATION_MS.extractValue(
            flowContext, request, response);

    assertThat(value).isEqualTo("5000");
  }

  @Test
  void testTcpClientConnectionDurationFieldWhenNull() {
    when(flowContext.getTimingData("tcp_client_connection_duration_ms")).thenReturn(null);

    String value =
        StandardField.TCP_CLIENT_CONNECTION_DURATION_MS.extractValue(
            flowContext, request, response);

    assertThat(value).isNull();
  }

  @Test
  void testTcpServerConnectionDurationField() {
    when(flowContext.getTimingData("tcp_server_connection_duration_ms")).thenReturn(3000L);

    String value =
        StandardField.TCP_SERVER_CONNECTION_DURATION_MS.extractValue(
            flowContext, request, response);

    assertThat(value).isEqualTo("3000");
  }

  @Test
  void testSslHandshakeTimeField() {
    when(flowContext.getTimingData("ssl_handshake_time_ms")).thenReturn(200L);

    String value = StandardField.SSL_HANDSHAKE_TIME_MS.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("200");
  }

  @Test
  void testDnsResolutionTimeField() {
    when(flowContext.getTimingData("dns_resolution_time_ms")).thenReturn(50L);

    String value =
        StandardField.DNS_RESOLUTION_TIME_MS.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("50");
  }

  @Test
  void testResponseLatencyField() {
    when(flowContext.getTimingData("response_latency_ms")).thenReturn(150L);

    String value = StandardField.RESPONSE_LATENCY_MS.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("150");
  }

  @Test
  void testResponseLatencyFieldWhenNull() {
    when(flowContext.getTimingData("response_latency_ms")).thenReturn(null);

    String value = StandardField.RESPONSE_LATENCY_MS.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testResponseTransferTimeField() {
    when(flowContext.getTimingData("response_transfer_time_ms")).thenReturn(75L);

    String value =
        StandardField.RESPONSE_TRANSFER_TIME_MS.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("75");
  }

  @Test
  void testSaturationCountField() {
    String value = StandardField.SATURATION_COUNT.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testExceptionTypeField() {
    String value = StandardField.EXCEPTION_TYPE.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testAllEnumConstantsExist() {
    assertThat(StandardField.values()).hasSize(21);
    assertThat(StandardField.values())
        .contains(
            StandardField.FLOW_ID,
            StandardField.TIMESTAMP,
            StandardField.CLIENT_IP,
            StandardField.REMOTE_IP,
            StandardField.METHOD,
            StandardField.URI,
            StandardField.STATUS,
            StandardField.BYTES,
            StandardField.HTTP_REQUEST_PROCESSING_TIME_MS,
            StandardField.REFERER,
            StandardField.USER_AGENT,
            StandardField.PROTOCOL,
            StandardField.TCP_CONNECTION_ESTABLISHMENT_TIME_MS,
            StandardField.TCP_CLIENT_CONNECTION_DURATION_MS,
            StandardField.TCP_SERVER_CONNECTION_DURATION_MS,
            StandardField.SSL_HANDSHAKE_TIME_MS,
            StandardField.DNS_RESOLUTION_TIME_MS,
            StandardField.RESPONSE_LATENCY_MS,
            StandardField.RESPONSE_TRANSFER_TIME_MS,
            StandardField.SATURATION_COUNT,
            StandardField.EXCEPTION_TYPE);
  }
}
