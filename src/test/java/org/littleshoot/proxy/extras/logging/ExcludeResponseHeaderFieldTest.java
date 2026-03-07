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

class ExcludeResponseHeaderFieldTest {

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
  void testConstructorWithString() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField("Set-Cookie|Authorization");

    assertThat(field.getName()).isEqualTo("res_all_except_set_cookie_authorization");
    assertThat(field.getDescription())
        .isEqualTo("Response headers excluding pattern: Set-Cookie|Authorization");
  }

  @Test
  void testConstructorWithTransformers() {
    ExcludeResponseHeaderField field =
        new ExcludeResponseHeaderField(
            "Set-Cookie", headerName -> "custom_" + headerName, value -> value.toUpperCase());

    assertThat(field.getName()).isEqualTo("res_all_except_set_cookie");
  }

  @Test
  void testGetName() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField("X-.*");

    assertThat(field.getName()).isEqualTo("res_all_except_x___");
  }

  @Test
  void testGetDescription() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField("Cache-Control");

    assertThat(field.getDescription())
        .isEqualTo("Response headers excluding pattern: Cache-Control");
  }

  @Test
  void testExtractValue() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField("Set-Cookie");

    String value = field.extractValue(flowContext, request, response);

    assertThat(value).isEqualTo("-");
  }

  @Test
  void testExtractMatchingHeaders() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField("Set-Cookie|Authorization");

    when(headers.names())
        .thenReturn(Set.of("Content-Type", "Set-Cookie", "X-Request-ID", "Authorization"));
    when(headers.get("Content-Type")).thenReturn("application/json");
    when(headers.get("X-Request-ID")).thenReturn("resp-123");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsEntry("res_content_type", "application/json");
    assertThat(matches).containsEntry("res_x_request_id", "resp-123");
    assertThat(matches).doesNotContainKey("res_set_cookie");
    assertThat(matches).doesNotContainKey("res_authorization");
  }

  @Test
  void testExtractMatchingHeadersNoExclusions() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField("NonExistent.*");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Accept"));
    when(headers.get("Content-Type")).thenReturn("application/json");
    when(headers.get("Accept")).thenReturn("*/*");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).hasSize(2);
    assertThat(matches).containsEntry("res_content_type", "application/json");
    assertThat(matches).containsEntry("res_accept", "*/*");
  }

  @Test
  void testExtractMatchingHeadersAllExcluded() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField(".*");

    when(headers.names()).thenReturn(Set.of("Content-Type", "Accept"));

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).isEmpty();
  }

  @Test
  void testExtractMatchingHeadersWithTransformer() {
    ExcludeResponseHeaderField field =
        new ExcludeResponseHeaderField(
            "Set-Cookie", headerName -> "custom_" + headerName, value -> value.toUpperCase());

    when(headers.names()).thenReturn(Set.of("Content-Type"));
    when(headers.get("Content-Type")).thenReturn("json");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("custom_Content-Type", "JSON");
  }

  @Test
  void testExtractMatchingHeadersWithNullValue() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField("Set-Cookie");

    when(headers.names()).thenReturn(Set.of("X-Header"));
    when(headers.get("X-Header")).thenReturn(null);

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).containsEntry("res_x_header", "-");
  }

  @Test
  void testExtractMatchingHeadersEmpty() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField("Set-Cookie");

    when(headers.names()).thenReturn(Set.of());

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    assertThat(matches).isEmpty();
  }

  @Test
  void testExtractMatchingHeadersCaseSensitive() {
    ExcludeResponseHeaderField field = new ExcludeResponseHeaderField("content-type");

    when(headers.names()).thenReturn(Set.of("Content-Type", "X-Header"));
    when(headers.get("Content-Type")).thenReturn("json");
    when(headers.get("X-Header")).thenReturn("value");

    Map<String, String> matches = field.extractMatchingHeaders(headers);

    // Neither matches "content-type" (lowercase), so both should be included
    assertThat(matches).hasSize(2);
    assertThat(matches).containsKey("res_content_type");
    assertThat(matches).containsKey("res_x_header");
  }
}
