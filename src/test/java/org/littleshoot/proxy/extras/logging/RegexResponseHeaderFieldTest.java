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

class RegexResponseHeaderFieldTest {

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
  void testConstructorWithPattern() {
    Pattern pattern = Pattern.compile("X-RateLimit-.*");
    RegexResponseHeaderField field = new RegexResponseHeaderField(pattern);

    assertThat(field.getPattern()).isEqualTo(pattern);
    // X-RateLimit-.* -> lowercase -> x-ratelimit-.* -> replace non-alphanumeric -> x_ratelimit___
    // (3 underscores for -, ., *)
    assertThat(field.getName()).isEqualTo("resp_x_ratelimit___*");
    assertThat(field.getDescription())
        .isEqualTo("Response headers matching pattern: X-RateLimit-.*");
  }

  @Test
  void testConstructorWithString() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-Cache-.*");

    assertThat(field.getPattern().pattern()).isEqualTo("X-Cache-.*");
  }

  @Test
  void testConstructorWithPatternAndTransformers() {
    Pattern pattern = Pattern.compile("X-.*");
    RegexResponseHeaderField field =
        new RegexResponseHeaderField(
            pattern, headerName -> "custom_" + headerName, value -> value.toUpperCase());

    assertThat(field.getPattern()).isEqualTo(pattern);
  }

  @Test
  void testGetName() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-.*-Status");

    assertThat(field.getName()).isEqualTo("resp_x____status*");
  }

  @Test
  void testGetDescription() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-Custom-.*");

    assertThat(field.getDescription()).isEqualTo("Response headers matching pattern: X-Custom-.*");
  }

  @Test
  void testExtractValue() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-.*");

    String value = field.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testExtractMatchingHeaders() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-RateLimit-.*");

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
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-Custom-.*");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Content-Length"));

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).isEmpty();
  }

  @Test
  void testExtractMatchingHeadersWithTransformer() {
    RegexResponseHeaderField field =
        new RegexResponseHeaderField(
            "X-.*", headerName -> "custom_" + headerName, value -> value.toUpperCase());

    when(headers.names()).thenReturn(Set.of("X-Test"));
    when(headers.get("X-Test")).thenReturn("value");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("custom_X-Test", "VALUE");
  }

  @Test
  void testExtractMatchingHeadersWithNullValue() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-.*");

    when(headers.names()).thenReturn(Set.of("X-Header"));
    when(headers.get("X-Header")).thenReturn(null);

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("res_x_header", "-");
  }

  @Test
  void testHasMatches() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-.*");

    when(headers.names()).thenReturn(Set.of("X-Custom-Header", "Content-Type"));

    assertThat(field.hasMatches(headers)).isTrue();
  }

  @Test
  void testHasMatchesNoMatches() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-Custom-.*");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Content-Length"));

    assertThat(field.hasMatches(headers)).isFalse();
  }

  @Test
  void testHasMatchesEmptyHeaders() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-.*");

    when(headers.names()).thenReturn(Set.of());

    assertThat(field.hasMatches(headers)).isFalse();
  }

  @Test
  void testGetPattern() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-API-.*");

    assertThat(field.getPattern().pattern()).isEqualTo("X-API-.*");
  }

  @Test
  void testEquals() {
    RegexResponseHeaderField field1 = new RegexResponseHeaderField("X-.*");
    RegexResponseHeaderField field2 = new RegexResponseHeaderField("X-.*");
    RegexResponseHeaderField field3 = new RegexResponseHeaderField("Y-.*");

    assertThat(field1).isEqualTo(field2);
    assertThat(field1).isNotEqualTo(field3);
    assertThat(field1).isNotEqualTo(null);
    assertThat(field1).isNotEqualTo("string");
  }

  @Test
  void testHashCode() {
    RegexResponseHeaderField field1 = new RegexResponseHeaderField("X-.*");
    RegexResponseHeaderField field2 = new RegexResponseHeaderField("X-.*");

    assertThat(field1.hashCode()).isEqualTo(field2.hashCode());
  }

  @Test
  void testToString() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-RateLimit-.*");

    assertThat(field.toString()).isEqualTo("RegexResponseHeaderField{pattern='X-RateLimit-.*'}");
  }

  @Test
  void testExtractMatchingHeadersSorted() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-.*");

    when(headers.names()).thenReturn(Set.of("X-Zebra", "X-Apple", "X-Mango"));
    when(headers.get("X-Zebra")).thenReturn("z");
    when(headers.get("X-Apple")).thenReturn("a");
    when(headers.get("X-Mango")).thenReturn("m");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    // Should be sorted alphabetically by field name (TreeMap)
    assertThat(matches.keySet()).containsExactly("res_x_apple", "res_x_mango", "res_x_zebra");
  }

  @Test
  void testExtractMatchingHeadersCaseSensitive() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-Custom-.*");

    when(headers.names()).thenReturn(Set.of("X-Custom-Header", "x-custom-header"));
    when(headers.get("X-Custom-Header")).thenReturn("value1");
    when(headers.get("x-custom-header")).thenReturn("value2");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(1);
    assertThat(matches).containsKey("res_x_custom_header");
    assertThat(matches.get("res_x_custom_header")).isEqualTo("value1");
  }

  @Test
  void testExtractMatchingHeadersWithComplexPattern() {
    RegexResponseHeaderField field = new RegexResponseHeaderField("X-(Cache|RateLimit)-.*");

    when(headers.names())
        .thenReturn(Set.of("X-Cache-Status", "X-RateLimit-Limit", "X-Other-Header"));
    when(headers.get("X-Cache-Status")).thenReturn("HIT");
    when(headers.get("X-RateLimit-Limit")).thenReturn("100");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsEntry("res_x_cache_status", "HIT");
    assertThat(matches).containsEntry("res_x_ratelimit_limit", "100");
  }
}
