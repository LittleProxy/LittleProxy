package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;

class ExcludeRequestHeaderFieldTest {

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

    when(request.headers()).thenReturn(headers);
  }

  @Test
  void testConstructorWithPattern() {
    Pattern pattern = Pattern.compile("Authorization|Cookie");
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField(pattern);

    assertThat(field.getExcludePattern()).isEqualTo(pattern);
    assertThat(field.getName()).isEqualTo("req_all_except_authorization_cookie");
    assertThat(field.getDescription())
        .isEqualTo("Request headers excluding pattern: Authorization|Cookie");
  }

  @Test
  void testConstructorWithString() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("Authorization|Cookie");

    assertThat(field.getExcludePattern().pattern()).isEqualTo("Authorization|Cookie");
  }

  @Test
  void testConstructorWithPatternAndTransformers() {
    Pattern pattern = Pattern.compile("Authorization");
    ExcludeRequestHeaderField field =
        new ExcludeRequestHeaderField(
            pattern, headerName -> "custom_" + headerName, value -> value.toUpperCase());

    assertThat(field.getExcludePattern()).isEqualTo(pattern);
  }

  @Test
  void testGetName() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("Authorization.*");

    // authorization.* -> authorization__ (2 underscores for . and *)
    assertThat(field.getName()).isEqualTo("req_all_except_authorization__");
  }

  @Test
  void testGetDescription() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("Cookie.*");

    assertThat(field.getDescription()).isEqualTo("Request headers excluding pattern: Cookie.*");
  }

  @Test
  void testExtractValue() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("Authorization");

    String value = field.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testExtractMatchingHeaders() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("Authorization|Cookie");

    when(headers.names())
        .thenReturn(Set.of("Content-Type", "Authorization", "X-Request-ID", "Cookie"));
    when(headers.get("Content-Type")).thenReturn("application/json");
    when(headers.get("X-Request-ID")).thenReturn("req-123");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsEntry("req_content_type", "application/json");
    assertThat(matches).containsEntry("req_x_request_id", "req-123");
    assertThat(matches).doesNotContainKey("req_authorization");
    assertThat(matches).doesNotContainKey("req_cookie");
  }

  @Test
  void testExtractMatchingHeadersNoExclusions() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("NonExistent.*");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Accept"));
    when(headers.get("Content-Type")).thenReturn("application/json");
    when(headers.get("Accept")).thenReturn("*/*");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsEntry("req_content_type", "application/json");
    assertThat(matches).containsEntry("req_accept", "*/*");
  }

  @Test
  void testExtractMatchingHeadersAllExcluded() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField(".*");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Accept"));

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).isEmpty();
  }

  @Test
  void testExtractMatchingHeadersWithTransformer() {
    ExcludeRequestHeaderField field =
        new ExcludeRequestHeaderField(
            "Authorization", headerName -> "custom_" + headerName, value -> value.toUpperCase());

    when(headers.names()).thenReturn(Set.of("Content-Type"));
    when(headers.get("Content-Type")).thenReturn("json");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("custom_Content-Type", "JSON");
  }

  @Test
  void testExtractMatchingHeadersWithNullValue() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("Authorization");

    when(headers.names()).thenReturn(Set.of("X-Header"));
    when(headers.get("X-Header")).thenReturn(null);

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("req_x_header", "-");
  }

  @Test
  void testEquals() {
    ExcludeRequestHeaderField field1 = new ExcludeRequestHeaderField("Authorization");
    ExcludeRequestHeaderField field2 = new ExcludeRequestHeaderField("Authorization");
    ExcludeRequestHeaderField field3 = new ExcludeRequestHeaderField("Cookie");

    assertThat(field1).isEqualTo(field2);
    assertThat(field1).isNotEqualTo(field3);
    assertThat(field1).isNotEqualTo(null);
    assertThat(field1).isNotEqualTo("string");
  }

  @Test
  void testHashCode() {
    ExcludeRequestHeaderField field1 = new ExcludeRequestHeaderField("Authorization");
    ExcludeRequestHeaderField field2 = new ExcludeRequestHeaderField("Authorization");

    assertThat(field1.hashCode()).isEqualTo(field2.hashCode());
  }

  @Test
  void testToString() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("Authorization|Cookie");

    assertThat(field.toString())
        .isEqualTo("ExcludeRequestHeaderField{excludePattern='Authorization|Cookie'}");
  }

  @Test
  void testExtractMatchingHeadersCaseSensitive() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("authorization");

    when(headers.names()).thenReturn(Set.of("Authorization", "content-type"));
    when(headers.get("Authorization")).thenReturn("bearer");
    when(headers.get("content-type")).thenReturn("json");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    // Neither matches "authorization" (lowercase), so both should be included
    assertThat(matches).hasSize(2);
    assertThat(matches).containsKey("req_authorization");
    assertThat(matches).containsKey("req_content_type");
  }

  @Test
  void testExtractMatchingHeadersEmpty() {
    ExcludeRequestHeaderField field = new ExcludeRequestHeaderField("Authorization");

    when(headers.names()).thenReturn(Set.of());

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).isEmpty();
  }
}
