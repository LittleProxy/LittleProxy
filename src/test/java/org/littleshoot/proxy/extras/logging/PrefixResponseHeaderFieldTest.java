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

class PrefixResponseHeaderFieldTest {

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
  void testConstructorWithPrefix() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-RateLimit-");

    assertThat(field.getPrefix()).isEqualTo("X-RateLimit-");
    assertThat(field.getName()).isEqualTo("resp_x_ratelimit_*");
    assertThat(field.getDescription()).isEqualTo("Response headers matching prefix: X-RateLimit-");
  }

  @Test
  void testConstructorWithPrefixAndFieldNameTransformer() {
    PrefixResponseHeaderField field =
        new PrefixResponseHeaderField("X-Custom-", headerName -> "custom_" + headerName);

    assertThat(field.getPrefix()).isEqualTo("X-Custom-");
    assertThat(field.getName()).isEqualTo("resp_x_custom_*");
  }

  @Test
  void testConstructorWithAllTransformers() {
    PrefixResponseHeaderField field =
        new PrefixResponseHeaderField(
            "X-Custom-", headerName -> "custom_" + headerName, value -> value.toUpperCase());

    assertThat(field.getPrefix()).isEqualTo("X-Custom-");
  }

  @Test
  void testGetName() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-Cache-");

    assertThat(field.getName()).isEqualTo("resp_x_cache_*");

    PrefixResponseHeaderField field2 = new PrefixResponseHeaderField("Set-Cookie");
    assertThat(field2.getName()).isEqualTo("resp_set_cookie*");
  }

  @Test
  void testGetDescription() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-Custom-");

    assertThat(field.getDescription()).isEqualTo("Response headers matching prefix: X-Custom-");
  }

  @Test
  void testExtractValue() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-.*");

    String value = field.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testExtractMatchingHeaders() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-RateLimit-");

    when(headers.names())
        .thenReturn(Set.of("X-RateLimit-Limit", "X-RateLimit-Remaining", "Content-Type"));
    when(headers.get("X-RateLimit-Limit")).thenReturn("100");
    when(headers.get("X-RateLimit-Remaining")).thenReturn("99");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsEntry("res_x_ratelimit_limit", "100");
    assertThat(matches).containsEntry("res_x_ratelimit_remaining", "99");
  }

  @Test
  void testExtractMatchingHeadersNoMatches() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-Custom-");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Content-Length"));

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).isEmpty();
  }

  @Test
  void testExtractMatchingHeadersWithTransformer() {
    PrefixResponseHeaderField field =
        new PrefixResponseHeaderField(
            "X-", headerName -> "custom_" + headerName, value -> value.toUpperCase());

    when(headers.names()).thenReturn(Set.of("X-Test"));
    when(headers.get("X-Test")).thenReturn("value");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("custom_X-Test", "VALUE");
  }

  @Test
  void testExtractMatchingHeadersWithNullValue() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-Custom-");

    when(headers.names()).thenReturn(Set.of("X-Custom-Header"));
    when(headers.get("X-Custom-Header")).thenReturn(null);

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("res_x_custom_header", "-");
  }

  @Test
  void testHasMatches() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-Cache-");

    when(headers.names()).thenReturn(Set.of("X-Cache-Status", "Content-Type"));

    assertThat(field.hasMatches(headers)).isTrue();
  }

  @Test
  void testHasMatchesNoMatches() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-Custom-");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Content-Length"));

    assertThat(field.hasMatches(headers)).isFalse();
  }

  @Test
  void testHasMatchesEmptyHeaders() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-.*");

    when(headers.names()).thenReturn(Set.of());

    assertThat(field.hasMatches(headers)).isFalse();
  }

  @Test
  void testGetPrefix() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-API-");

    assertThat(field.getPrefix()).isEqualTo("X-API-");
  }

  @Test
  void testEquals() {
    PrefixResponseHeaderField field1 = new PrefixResponseHeaderField("X-Custom-");
    PrefixResponseHeaderField field2 = new PrefixResponseHeaderField("X-Custom-");
    PrefixResponseHeaderField field3 = new PrefixResponseHeaderField("X-Other-");

    assertThat(field1).isEqualTo(field2);
    assertThat(field1).isNotEqualTo(field3);
    assertThat(field1).isNotEqualTo(null);
    assertThat(field1).isNotEqualTo("string");
  }

  @Test
  void testHashCode() {
    PrefixResponseHeaderField field1 = new PrefixResponseHeaderField("X-Custom-");
    PrefixResponseHeaderField field2 = new PrefixResponseHeaderField("X-Custom-");

    assertThat(field1.hashCode()).isEqualTo(field2.hashCode());
  }

  @Test
  void testToString() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-RateLimit-");

    assertThat(field.toString()).isEqualTo("PrefixResponseHeaderField{prefix='X-RateLimit-'}");
  }

  @Test
  void testExtractMatchingHeadersSorted() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-");

    when(headers.names()).thenReturn(Set.of("X-Zebra", "X-Apple", "X-Mango"));
    when(headers.get("X-Zebra")).thenReturn("z");
    when(headers.get("X-Apple")).thenReturn("a");
    when(headers.get("X-Mango")).thenReturn("m");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    // Should be sorted alphabetically by field name (TreeMap)
    assertThat(matches.keySet()).containsExactly("res_x_apple", "res_x_mango", "res_x_zebra");
  }

  @Test
  void testExtractMatchingHeadersWithEmptyPrefix() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("");

    when(headers.names()).thenReturn(Set.of("Header1", "Header2"));
    when(headers.get("Header1")).thenReturn("value1");
    when(headers.get("Header2")).thenReturn("value2");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    // Empty prefix matches all headers
    assertThat(matches).hasSize(2);
  }

  @Test
  void testDefaultFieldNameTransformation() {
    PrefixResponseHeaderField field = new PrefixResponseHeaderField("X-Custom-Header");

    when(headers.names()).thenReturn(Set.of("X-Custom-Header-Value"));
    when(headers.get("X-Custom-Header-Value")).thenReturn("test");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    // Default transformation: lowercase and replace non-alphanumeric with underscore
    // Note: PrefixResponseHeaderField uses "res_" prefix (not "resp_") in defaultFieldName
    assertThat(matches).containsKey("res_x_custom_header_value");
  }
}
