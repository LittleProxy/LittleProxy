package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;

class ResponseHeaderFieldTest {

  private FlowContext flowContext;
  private HttpRequest request;
  private HttpResponse response;
  private HttpHeaders headers;

  @BeforeEach
  void setUp() {
    flowContext = mock(FlowContext.class);
    request = mock(HttpRequest.class);
    response = mock(HttpResponse.class);
    headers = mock(HttpHeaders.class);

    when(response.headers()).thenReturn(headers);
  }

  @Test
  void testConstructorWithHeaderName() {
    ResponseHeaderField field = new ResponseHeaderField("Content-Type");

    assertThat(field.getHeaderName()).isEqualTo("Content-Type");
    assertThat(field.getName()).isEqualTo("resp_content_type");
    assertThat(field.getDescription()).isEqualTo("Response header: Content-Type");
  }

  @Test
  void testConstructorWithHeaderNameAndFieldName() {
    ResponseHeaderField field = new ResponseHeaderField("Content-Type", "content_type");

    assertThat(field.getHeaderName()).isEqualTo("Content-Type");
    assertThat(field.getName()).isEqualTo("content_type");
    assertThat(field.getDescription()).isEqualTo("Response header: Content-Type");
  }

  @Test
  void testGetName() {
    ResponseHeaderField field = new ResponseHeaderField("Cache-Control");

    assertThat(field.getName()).isEqualTo("resp_cache_control");
  }

  @Test
  void testGetNameWithCustomFieldName() {
    ResponseHeaderField field = new ResponseHeaderField("Cache-Control", "cache");

    assertThat(field.getName()).isEqualTo("cache");
  }

  @Test
  void testGetDescription() {
    ResponseHeaderField field = new ResponseHeaderField("Server");

    assertThat(field.getDescription()).isEqualTo("Response header: Server");
  }

  @Test
  void testExtractValue() {
    ResponseHeaderField field = new ResponseHeaderField("X-Custom-Header");

    when(headers.get("X-Custom-Header")).thenReturn("custom-value");

    String value = field.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("custom-value");
  }

  @Test
  void testExtractValueWhenMissing() {
    ResponseHeaderField field = new ResponseHeaderField("X-Custom-Header");

    when(headers.get("X-Custom-Header")).thenReturn(null);

    String value = field.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testGetHeaderName() {
    ResponseHeaderField field = new ResponseHeaderField("X-Response-ID");

    assertThat(field.getHeaderName()).isEqualTo("X-Response-ID");
  }

  @Test
  void testEquals() {
    ResponseHeaderField field1 = new ResponseHeaderField("Content-Type");
    ResponseHeaderField field2 = new ResponseHeaderField("Content-Type");
    ResponseHeaderField field3 = new ResponseHeaderField("Content-Type", "different_name");
    ResponseHeaderField field4 = new ResponseHeaderField("Content-Length");

    assertThat(field1).isEqualTo(field2);
    assertThat(field1).isEqualTo(field3); // Same header name
    assertThat(field1).isNotEqualTo(field4);
    assertThat(field1).isNotEqualTo(null);
    assertThat(field1).isNotEqualTo("not a field");
  }

  @Test
  void testHashCode() {
    ResponseHeaderField field1 = new ResponseHeaderField("Content-Type");
    ResponseHeaderField field2 = new ResponseHeaderField("Content-Type");

    assertThat(field1.hashCode()).isEqualTo(field2.hashCode());
  }

  @Test
  void testToString() {
    ResponseHeaderField field = new ResponseHeaderField("Content-Type");

    assertThat(field.toString()).contains("ResponseHeaderField");
    assertThat(field.toString()).contains("Content-Type");
    assertThat(field.toString()).contains("resp_content_type");
  }

  @Test
  void testFieldNameSanitization() {
    ResponseHeaderField field = new ResponseHeaderField("X-Custom-Header!@#");

    assertThat(field.getName()).isEqualTo("resp_x_custom_header___");
  }

  @Test
  void testFieldNameWithNumbers() {
    ResponseHeaderField field = new ResponseHeaderField("X-API-Version-2");

    assertThat(field.getName()).isEqualTo("resp_x_api_version_2");
  }
}
