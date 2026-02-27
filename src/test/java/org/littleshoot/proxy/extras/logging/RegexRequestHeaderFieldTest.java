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

class RegexRequestHeaderFieldTest {

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
    Pattern pattern = Pattern.compile("X-.*-ID");
    RegexRequestHeaderField field = new RegexRequestHeaderField(pattern);

    assertThat(field.getPattern()).isEqualTo(pattern);
    // The pattern "X-.*-ID" becomes "x____id" after replacing non-alphanumeric chars with
    // underscore
    // x-.*-ID -> lowercase -> x-.*-id -> replace -> x____id
    assertThat(field.getName()).isEqualTo("req_x____id*");
    assertThat(field.getDescription()).isEqualTo("Request headers matching pattern: X-.*-ID");
  }

  @Test
  void testConstructorWithString() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-.*-ID");

    assertThat(field.getPattern().pattern()).isEqualTo("X-.*-ID");
  }

  @Test
  void testConstructorWithPatternAndTransformers() {
    Pattern pattern = Pattern.compile("X-.*");
    RegexRequestHeaderField field =
        new RegexRequestHeaderField(
            pattern, headerName -> "custom_" + headerName, value -> value.toUpperCase());

    assertThat(field.getPattern()).isEqualTo(pattern);
  }

  @Test
  void testGetName() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-Request-.*");

    // x-request-.* -> lowercase -> x-request-.* -> replace -> x_request___ (3 underscores for -, .,
    // *)
    assertThat(field.getName()).isEqualTo("req_x_request___*");
  }

  @Test
  void testGetDescription() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("Authorization.*");

    assertThat(field.getDescription())
        .isEqualTo("Request headers matching pattern: Authorization.*");
  }

  @Test
  void testExtractValue() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-.*");

    String value = field.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testExtractMatchingHeaders() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-.*-ID");

    when(headers.names()).thenReturn(Set.of("X-Request-ID", "X-Response-ID", "Content-Type"));
    when(headers.get("X-Request-ID")).thenReturn("req-123");
    when(headers.get("X-Response-ID")).thenReturn("resp-456");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsEntry("req_x_request_id", "req-123");
    assertThat(matches).containsEntry("req_x_response_id", "resp-456");
  }

  @Test
  void testExtractMatchingHeadersNoMatches() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-Custom-.*");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Authorization"));

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).isEmpty();
  }

  @Test
  void testExtractMatchingHeadersWithTransformer() {
    RegexRequestHeaderField field =
        new RegexRequestHeaderField(
            "X-.*", headerName -> "custom_" + headerName, value -> value.toUpperCase());

    when(headers.names()).thenReturn(Set.of("X-Test"));
    when(headers.get("X-Test")).thenReturn("value");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("custom_X-Test", "VALUE");
  }

  @Test
  void testExtractMatchingHeadersWithNullValue() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-.*");

    when(headers.names()).thenReturn(Set.of("X-Header"));
    when(headers.get("X-Header")).thenReturn(null);

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("req_x_header", "-");
  }

  @Test
  void testHasMatches() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-.*");

    when(headers.names()).thenReturn(Set.of("X-Custom-Header", "Content-Type"));

    assertThat(field.hasMatches(headers)).isTrue();
  }

  @Test
  void testHasMatchesNoMatches() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-Custom-.*");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Authorization"));

    assertThat(field.hasMatches(headers)).isFalse();
  }

  @Test
  void testHasMatchesEmptyHeaders() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-.*");

    when(headers.names()).thenReturn(Set.of());

    assertThat(field.hasMatches(headers)).isFalse();
  }

  @Test
  void testEquals() {
    RegexRequestHeaderField field1 = new RegexRequestHeaderField("X-.*");
    RegexRequestHeaderField field2 = new RegexRequestHeaderField("X-.*");
    RegexRequestHeaderField field3 = new RegexRequestHeaderField("Y-.*");

    assertThat(field1).isEqualTo(field2);
    assertThat(field1).isNotEqualTo(field3);
    assertThat(field1).isNotEqualTo(null);
    assertThat(field1).isNotEqualTo("string");
  }

  @Test
  void testHashCode() {
    RegexRequestHeaderField field1 = new RegexRequestHeaderField("X-.*");
    RegexRequestHeaderField field2 = new RegexRequestHeaderField("X-.*");

    assertThat(field1.hashCode()).isEqualTo(field2.hashCode());
  }

  @Test
  void testToString() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-.*-ID");

    assertThat(field.toString()).isEqualTo("RegexRequestHeaderField{pattern='X-.*-ID'}");
  }

  @Test
  void testExtractMatchingHeadersCaseSensitive() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-Custom-.*");

    when(headers.names()).thenReturn(Set.of("X-Custom-Header", "x-custom-header"));
    when(headers.get("X-Custom-Header")).thenReturn("value1");
    when(headers.get("x-custom-header")).thenReturn("value2");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(1);
    assertThat(matches).containsKey("req_x_custom_header");
  }

  @Test
  void testExtractMatchingHeadersWithComplexPattern() {
    RegexRequestHeaderField field = new RegexRequestHeaderField("X-(Request|Response)-ID");

    when(headers.names()).thenReturn(Set.of("X-Request-ID", "X-Response-ID", "X-Other-ID"));
    when(headers.get("X-Request-ID")).thenReturn("req");
    when(headers.get("X-Response-ID")).thenReturn("resp");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsEntry("req_x_request_id", "req");
    assertThat(matches).containsEntry("req_x_response_id", "resp");
  }
}
