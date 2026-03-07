package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;

class PrefixRequestHeaderFieldTest {

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
  }

  @Test
  void testConstructorWithPrefix() {
    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    assertThat(field.getPrefix()).isEqualTo("X-Custom-");
    assertThat(field.getName()).isEqualTo("req_x_custom_*");
    assertThat(field.getDescription()).isEqualTo("Request headers matching prefix: X-Custom-");
  }

  @Test
  void testConstructorWithPrefixAndFieldNameTransformer() {
    PrefixRequestHeaderField field =
        new PrefixRequestHeaderField("X-Custom-", headerName -> "custom_" + headerName);
    assertThat(field.getPrefix()).isEqualTo("X-Custom-");
    assertThat(field.getName()).isEqualTo("req_x_custom_*");
  }

  @Test
  void testConstructorWithAllTransformers() {
    PrefixRequestHeaderField field =
        new PrefixRequestHeaderField(
            "X-Custom-", headerName -> "custom_" + headerName, value -> value.toUpperCase());
    assertThat(field.getPrefix()).isEqualTo("X-Custom-");
    assertThat(field.getName()).isEqualTo("req_x_custom_*");
  }

  @Test
  void testGetName() {
    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    assertThat(field.getName()).isEqualTo("req_x_custom_*");

    PrefixRequestHeaderField field2 = new PrefixRequestHeaderField("Authorization");
    assertThat(field2.getName()).isEqualTo("req_authorization*");

    PrefixRequestHeaderField field3 = new PrefixRequestHeaderField("X-Test-Header");
    assertThat(field3.getName()).isEqualTo("req_x_test_header*");
  }

  @Test
  void testGetDescription() {
    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    assertThat(field.getDescription()).isEqualTo("Request headers matching prefix: X-Custom-");

    PrefixRequestHeaderField field2 = new PrefixRequestHeaderField("Authorization");
    assertThat(field2.getDescription()).isEqualTo("Request headers matching prefix: Authorization");
  }

  @Test
  void testExtractValue() {
    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    String value = field.extractValue(flowContext, request, response);
    assertThat(value).isEqualTo("-");
  }

  @Test
  void testExtractMatchingHeaders() {
    Set<String> headerNames = Set.of("X-Custom-Header1", "X-Custom-Header2", "Other-Header");
    when(headers.names()).thenReturn(headerNames);
    when(headers.get("X-Custom-Header1")).thenReturn("value1");
    when(headers.get("X-Custom-Header2")).thenReturn("value2");

    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsKey("req_x_custom_header1");
    assertThat(matches).containsKey("req_x_custom_header2");
    assertThat(matches.get("req_x_custom_header1")).isEqualTo("value1");
    assertThat(matches.get("req_x_custom_header2")).isEqualTo("value2");
  }

  @Test
  void testExtractMatchingHeadersNoMatches() {
    Set<String> headerNames = Set.of("Other-Header", "Another-Header");
    when(headers.names()).thenReturn(headerNames);

    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).isEmpty();
  }

  @Test
  void testExtractMatchingHeadersWithTransformer() {
    Set<String> headerNames = Set.of("X-Custom-Header1", "X-Custom-Header2");
    when(headers.names()).thenReturn(headerNames);
    when(headers.get("X-Custom-Header1")).thenReturn("value1");
    when(headers.get("X-Custom-Header2")).thenReturn("value2");

    PrefixRequestHeaderField field =
        new PrefixRequestHeaderField(
            "X-Custom-", headerName -> "custom_" + headerName, value -> value.toUpperCase());
    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsKey("custom_X-Custom-Header1");
    assertThat(matches).containsKey("custom_X-Custom-Header2");
    assertThat(matches.get("custom_X-Custom-Header1")).isEqualTo("VALUE1");
    assertThat(matches.get("custom_X-Custom-Header2")).isEqualTo("VALUE2");
  }

  @Test
  void testHasMatches() {
    Set<String> headerNames = Set.of("X-Custom-Header1", "X-Custom-Header2", "Other-Header");
    when(headers.names()).thenReturn(headerNames);

    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    boolean hasMatches = field.hasMatches(headers);

    assertThat(hasMatches).isTrue();
  }

  @Test
  void testHasMatchesNoMatches() {
    Set<String> headerNames = Set.of("Other-Header", "Another-Header");
    when(headers.names()).thenReturn(headerNames);

    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    boolean hasMatches = field.hasMatches(headers);

    assertThat(hasMatches).isFalse();
  }

  @Test
  void testGetPrefix() {
    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    assertThat(field.getPrefix()).isEqualTo("X-Custom-");

    PrefixRequestHeaderField field2 = new PrefixRequestHeaderField("Authorization");
    assertThat(field2.getPrefix()).isEqualTo("Authorization");
  }

  @Test
  void testEquals() {
    PrefixRequestHeaderField field1 = new PrefixRequestHeaderField("X-Custom-");
    PrefixRequestHeaderField field2 = new PrefixRequestHeaderField("X-Custom-");
    PrefixRequestHeaderField field3 = new PrefixRequestHeaderField("X-Other-");

    assertThat(field1).isEqualTo(field2);
    assertThat(field1).isNotEqualTo(field3);
    assertThat(field1).isNotEqualTo(null);
    assertThat(field1).isNotEqualTo("string");
  }

  @Test
  void testHashCode() {
    PrefixRequestHeaderField field1 = new PrefixRequestHeaderField("X-Custom-");
    PrefixRequestHeaderField field2 = new PrefixRequestHeaderField("X-Custom-");
    PrefixRequestHeaderField field3 = new PrefixRequestHeaderField("X-Other-");

    assertThat(field1.hashCode()).isEqualTo(field2.hashCode());
    assertThat(field1.hashCode()).isNotEqualTo(field3.hashCode());
  }

  @Test
  void testToString() {
    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    assertThat(field.toString()).isEqualTo("PrefixRequestHeaderField{prefix='X-Custom-'}");

    PrefixRequestHeaderField field2 = new PrefixRequestHeaderField("Authorization");
    assertThat(field2.toString()).isEqualTo("PrefixRequestHeaderField{prefix='Authorization'}");
  }

  @Test
  void testExtractMatchingHeadersWithNullValue() {
    Set<String> headerNames = Set.of("X-Custom-Header1", "X-Custom-Header2");
    when(headers.names()).thenReturn(headerNames);
    when(headers.get("X-Custom-Header1")).thenReturn("value1");
    when(headers.get("X-Custom-Header2")).thenReturn(null);

    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches.get("req_x_custom_header1")).isEqualTo("value1");
    assertThat(matches.get("req_x_custom_header2")).isEqualTo("-");
  }

  @Test
  void testExtractMatchingHeadersWithEmptyPrefix() {
    Set<String> headerNames = Set.of("Header1", "Header2", "X-Header");
    when(headers.names()).thenReturn(headerNames);
    when(headers.get("Header1")).thenReturn("value1");
    when(headers.get("Header2")).thenReturn("value2");
    when(headers.get("X-Header")).thenReturn("value3");

    PrefixRequestHeaderField field = new PrefixRequestHeaderField("");
    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(3);
    assertThat(matches).containsKeys("req_header1", "req_header2", "req_x_header");
  }

  @Test
  void testExtractMatchingHeadersCaseSensitive() {
    Set<String> headerNames = Set.of("X-Custom-Header", "x-custom-header");
    when(headers.names()).thenReturn(headerNames);
    when(headers.get("X-Custom-Header")).thenReturn("value1");
    when(headers.get("x-custom-header")).thenReturn("value2");

    PrefixRequestHeaderField field = new PrefixRequestHeaderField("X-Custom-");
    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(1);
    assertThat(matches).containsKey("req_x_custom_header");
    assertThat(matches.get("req_x_custom_header")).isEqualTo("value1");
  }
}
