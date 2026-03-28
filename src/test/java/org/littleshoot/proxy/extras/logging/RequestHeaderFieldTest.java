package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;
import org.mockito.Mockito;

class RequestHeaderFieldTest {

  @Test
  void testConstructorWithHeaderName() {
    // Given
    String headerName = "User-Agent";

    // When
    RequestHeaderField field = new RequestHeaderField(headerName);

    // Then
    assertThat(field.getHeaderName()).isEqualTo(headerName);
    assertThat(field.getName()).isEqualTo("req_user_agent");
    assertThat(field.getDescription()).isEqualTo("Request header: User-Agent");
  }

  @Test
  void testConstructorWithHeaderNameAndFieldName() {
    // Given
    String headerName = "Authorization";
    String customFieldName = "custom_auth";

    // When
    RequestHeaderField field = new RequestHeaderField(headerName, customFieldName);

    // Then
    assertThat(field.getHeaderName()).isEqualTo(headerName);
    assertThat(field.getName()).isEqualTo(customFieldName);
    assertThat(field.getDescription()).isEqualTo("Request header: Authorization");
  }

  @Test
  void testGetName() {
    // Given
    RequestHeaderField field = new RequestHeaderField("Content-Type");

    // When
    String name = field.getName();

    // Then
    assertThat(name).isEqualTo("req_content_type");
  }

  @Test
  void testGetNameWithCustomFieldName() {
    // Given
    RequestHeaderField field = new RequestHeaderField("Accept", "custom_accept");

    // When
    String name = field.getName();

    // Then
    assertThat(name).isEqualTo("custom_accept");
  }

  @Test
  void testGetDescription() {
    // Given
    RequestHeaderField field = new RequestHeaderField("Host");

    // When
    String description = field.getDescription();

    // Then
    assertThat(description).isEqualTo("Request header: Host");
  }

  @Test
  void testExtractValue() {
    // Given
    FlowContext flowContext = Mockito.mock(FlowContext.class);
    HttpRequest request = Mockito.mock(HttpRequest.class);
    HttpResponse response = Mockito.mock(HttpResponse.class);
    io.netty.handler.codec.http.HttpHeaders headers =
        Mockito.mock(io.netty.handler.codec.http.HttpHeaders.class);

    when(request.headers()).thenReturn(headers);
    when(headers.get("X-Custom-Header")).thenReturn("custom-value");

    RequestHeaderField field = new RequestHeaderField("X-Custom-Header");

    // When
    String value = field.extractValue(flowContext, request, response);

    // Then
    assertThat(value).isEqualTo("custom-value");
  }

  @Test
  void testExtractValueWhenMissing() {
    // Given
    FlowContext flowContext = Mockito.mock(FlowContext.class);
    HttpRequest request = Mockito.mock(HttpRequest.class);
    HttpResponse response = Mockito.mock(HttpResponse.class);
    io.netty.handler.codec.http.HttpHeaders headers =
        Mockito.mock(io.netty.handler.codec.http.HttpHeaders.class);

    when(request.headers()).thenReturn(headers);
    when(headers.get("Non-Existent-Header")).thenReturn(null);

    RequestHeaderField field = new RequestHeaderField("Non-Existent-Header");

    // When
    String value = field.extractValue(flowContext, request, response);

    // Then
    assertThat(value).isEqualTo("-");
  }

  @Test
  void testGetHeaderName() {
    // Given
    RequestHeaderField field = new RequestHeaderField("Cache-Control");

    // When
    String headerName = field.getHeaderName();

    // Then
    assertThat(headerName).isEqualTo("Cache-Control");
  }

  @Test
  void testEquals() {
    // Given
    RequestHeaderField field1 = new RequestHeaderField("Accept-Encoding");
    RequestHeaderField field2 = new RequestHeaderField("Accept-Encoding");
    RequestHeaderField field3 = new RequestHeaderField("Content-Length");

    // When/Then
    assertThat(field1).isEqualTo(field2);
    assertThat(field1).isNotEqualTo(field3);
    assertThat(field1).isNotEqualTo(null);
    assertThat(field1).isNotEqualTo("string");
  }

  @Test
  void testHashCode() {
    // Given
    RequestHeaderField field1 = new RequestHeaderField("Connection");
    RequestHeaderField field2 = new RequestHeaderField("Connection");
    RequestHeaderField field3 = new RequestHeaderField("Keep-Alive");

    // When/Then
    assertThat(field1.hashCode()).isEqualTo(field2.hashCode());
    assertThat(field1.hashCode()).isNotEqualTo(field3.hashCode());
  }

  @Test
  void testToString() {
    // Given
    RequestHeaderField field = new RequestHeaderField("User-Agent");

    // When
    String toString = field.toString();

    // Then
    assertThat(toString).contains("RequestHeaderField");
    assertThat(toString).contains("headerName='User-Agent'");
    assertThat(toString).contains("fieldName='req_user_agent'");
  }

  @Test
  void testFieldNameSanitization() {
    // Given
    RequestHeaderField field1 = new RequestHeaderField("X-Custom-Header");
    RequestHeaderField field2 = new RequestHeaderField("X_Custom_Header");
    RequestHeaderField field3 = new RequestHeaderField("X.Custom.Header");

    // When/Then
    assertThat(field1.getName()).isEqualTo("req_x_custom_header");
    assertThat(field2.getName()).isEqualTo("req_x_custom_header");
    assertThat(field3.getName()).isEqualTo("req_x_custom_header");
  }
}
