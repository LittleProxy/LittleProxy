package org.littleshoot.proxy.impl;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.impl.ProxyUtils.parseHostAndPort;

import io.netty.handler.codec.http.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Test for proxy utilities. */
@SuppressWarnings("deprecation")
public final class ProxyUtilsTest {

  @Test
  public void testParseHostAndPort() {
    assertThat(parseHostAndPort("http://www.test.com:80/test")).isEqualTo("www.test.com:80");
    assertThat(parseHostAndPort("https://www.test.com:80/test")).isEqualTo("www.test.com:80");
    assertThat(parseHostAndPort("https://www.test.com:443/test")).isEqualTo("www.test.com:443");
    assertThat(parseHostAndPort("www.test.com:80/test")).isEqualTo("www.test.com:80");
    assertThat(parseHostAndPort("http://www.test.com")).isEqualTo("www.test.com");
    assertThat(parseHostAndPort("www.test.com")).isEqualTo("www.test.com");
    assertThat(parseHostAndPort("httpbin.org:443/get")).isEqualTo("httpbin.org:443");
    assertThat(parseHostAndPort("")).isEqualTo("");
    assertThat(parseHostAndPort("invalid")).isEqualTo("invalid");
    assertThat(parseHostAndPort("invalid://")).isEqualTo("invalid:");
  }

  @Test
  public void testAddNewViaHeader() {
    HttpMessage httpMessage = new DefaultFullHttpRequest(HTTP_1_1, HttpMethod.GET, "/endpoint");
    ProxyUtils.addVia(httpMessage, "hostname");

    List<String> viaHeaders = httpMessage.headers().getAll(HttpHeaderNames.VIA);
    assertThat(viaHeaders).containsExactly("1.1 hostname");
  }

  @Test
  public void testGetHeaderValuesWhenHeadersAreEmpty() {
    DefaultHttpMessage message = new DefaultHttpResponse(HTTP_1_1, OK);
    List<String> commaSeparatedHeaders =
        ProxyUtils.getAllCommaSeparatedHeaderValues(TRANSFER_ENCODING, message);
    assertThat(commaSeparatedHeaders).isEmpty();
  }

  @Test
  public void testGetHeaderValuesWhenTwoHeadersWithNoValuesArePresent() {
    DefaultHttpMessage message = new DefaultHttpResponse(HTTP_1_1, OK);
    message.headers().add(TRANSFER_ENCODING, "");
    message.headers().add(TRANSFER_ENCODING, "");
    List<String> commaSeparatedHeaders =
        ProxyUtils.getAllCommaSeparatedHeaderValues(TRANSFER_ENCODING, message);
    assertThat(commaSeparatedHeaders).isEmpty();
  }

  @Test
  public void testGetHeaderValuesWhenSingleHeaderValueIsPresent() {
    DefaultHttpMessage message = new DefaultHttpResponse(HTTP_1_1, OK);
    message.headers().add(TRANSFER_ENCODING, "chunked");
    List<String> commaSeparatedHeaders =
        ProxyUtils.getAllCommaSeparatedHeaderValues(TRANSFER_ENCODING, message);
    assertThat(commaSeparatedHeaders).containsExactly("chunked");
  }

  @Test
  public void testGetHeaderValuesWhenSingleHeaderValueWithExtraSpacesIsPresent() {
    DefaultHttpMessage message = new DefaultHttpResponse(HTTP_1_1, OK, false);
    message.headers().add(TRANSFER_ENCODING, " chunked  , ");
    List<String> commaSeparatedHeaders =
        ProxyUtils.getAllCommaSeparatedHeaderValues(TRANSFER_ENCODING, message);
    assertThat(commaSeparatedHeaders).containsExactly("chunked");
  }

  @Test
  public void testGetHeaderValuesWhenTwoCommaSeparatedValuesInOneHeaderLineArePresent() {
    DefaultHttpMessage message = new DefaultHttpResponse(HTTP_1_1, OK);
    message.headers().add(TRANSFER_ENCODING, "compress, gzip");
    List<String> commaSeparatedHeaders =
        ProxyUtils.getAllCommaSeparatedHeaderValues(TRANSFER_ENCODING, message);
    assertThat(commaSeparatedHeaders).containsExactly("compress", "gzip");
  }

  @Test
  public void
      testGetHeaderValuesWhenTwoCommaSeparatedValuesInOneHeaderLineWithSpuriousCommaAndSpaceArePresent() {
    // two comma-separated values in one header line with a spurious ',' and space. see RFC 7230
    // section 7
    // for information on empty list items (not all of which are valid header-values).
    DefaultHttpMessage message = new DefaultHttpResponse(HTTP_1_1, OK);
    message.headers().add(TRANSFER_ENCODING, "compress, gzip, ,");
    List<String> commaSeparatedHeaders =
        ProxyUtils.getAllCommaSeparatedHeaderValues(TRANSFER_ENCODING, message);
    assertThat(commaSeparatedHeaders).containsExactly("compress", "gzip");
  }

  @Test
  public void testGetHeaderValuesWhenTwoValuesInTwoSeparateHeaderLinesArePresent() {
    DefaultHttpMessage message = new DefaultHttpResponse(HTTP_1_1, OK);
    message.headers().add(TRANSFER_ENCODING, "gzip");
    message.headers().add(TRANSFER_ENCODING, "chunked");
    List<String> commaSeparatedHeaders =
        ProxyUtils.getAllCommaSeparatedHeaderValues(TRANSFER_ENCODING, message);
    assertThat(commaSeparatedHeaders).containsExactly("gzip", "chunked");
  }

  @Test
  public void
      testGetHeaderValuesWhenMultipleCommaSeparatedValuesInTwoSeparateHeaderLinesArePresent() {
    DefaultHttpMessage message = new DefaultHttpResponse(HTTP_1_1, OK);
    message.headers().add(TRANSFER_ENCODING, "gzip, compress");
    message.headers().add(TRANSFER_ENCODING, "deflate, gzip");
    List<String> commaSeparatedHeaders =
        ProxyUtils.getAllCommaSeparatedHeaderValues(TRANSFER_ENCODING, message);
    assertThat(commaSeparatedHeaders).containsExactly("gzip", "compress", "deflate", "gzip");
  }

  @Test
  public void
      testGetHeaderValuesWhenMultipleCommaSeparatedValuesInMultipleSeparateHeaderLinesArePresent() {
    // multiple comma-separated values in multiple header lines with spurious spaces, commas,
    // and tabs (horizontal tabs are defined as optional whitespace in RFC 7230 section 3.2.3)
    DefaultHttpMessage message = new DefaultHttpResponse(HTTP_1_1, OK, false);
    message.headers().add(TRANSFER_ENCODING, " gzip,compress,");
    message.headers().add(TRANSFER_ENCODING, "\tdeflate\t,  gzip, ");
    message.headers().add(TRANSFER_ENCODING, ",gzip,,deflate,\t, ,");
    List<String> commaSeparatedHeaders =
        ProxyUtils.getAllCommaSeparatedHeaderValues(TRANSFER_ENCODING, message);
    assertThat(commaSeparatedHeaders)
        .containsExactly("gzip", "compress", "deflate", "gzip", "gzip", "deflate");
  }

  @Test
  public void testIsResponseSelfTerminating() {
    HttpResponse httpResponse;
    boolean isResponseSelfTerminating;

    // test cases from the scenarios listed in RFC 2616, section 4.4
    // #1: 1.Any response message which "MUST NOT" include a message-body (such as the 1xx, 204, and
    // 304 responses and any response to a HEAD request) is always terminated by the first empty
    // line after the header fields, regardless of the entity-header fields present in the message.
    httpResponse = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.CONTINUE);
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    httpResponse = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS);
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    httpResponse = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.NO_CONTENT);
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    httpResponse = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.RESET_CONTENT);
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    httpResponse = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    // #2: 2.If a Transfer-Encoding header field (section 14.41) is present and has any value other
    // than "identity", then the transfer-length is defined by use of the "chunked" transfer-coding
    // (section 3.6), unless the message is terminated by closing the connection.
    httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
    httpResponse.headers().add(TRANSFER_ENCODING, "chunked");
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
    httpResponse.headers().add(TRANSFER_ENCODING, "gzip, chunked");
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    // chunked encoding is not last, so not self terminating
    httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
    httpResponse.headers().add(TRANSFER_ENCODING, "chunked, gzip");
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isFalse();

    // four encodings on two lines, chunked is not last, so not self terminating
    httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
    httpResponse.headers().add(TRANSFER_ENCODING, "gzip, chunked");
    httpResponse.headers().add(TRANSFER_ENCODING, "deflate, gzip");
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isFalse();

    // three encodings on two lines, chunked is last, so self terminating
    httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
    httpResponse.headers().add(TRANSFER_ENCODING, "gzip");
    httpResponse.headers().add(TRANSFER_ENCODING, "deflate,chunked");
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    // #3: 3.If a Content-Length header field (section 14.13) is present, its decimal value in
    // OCTETs represents both the entity-length and the transfer-length.
    httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
    httpResponse.headers().add(HttpHeaderNames.CONTENT_LENGTH, "15");
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    // continuing #3: If a message is received with both a Transfer-Encoding header field and a
    // Content-Length header field, the latter MUST be ignored.

    // chunked is last Transfer-Encoding, so message is self-terminating
    httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
    httpResponse.headers().add(TRANSFER_ENCODING, "gzip, chunked");
    httpResponse.headers().add(HttpHeaderNames.CONTENT_LENGTH, "15");
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isTrue();

    // chunked is not last Transfer-Encoding, so message is not self-terminating, since
    // Content-Length is ignored
    httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
    httpResponse.headers().add(TRANSFER_ENCODING, "gzip");
    httpResponse.headers().add(HttpHeaderNames.CONTENT_LENGTH, "15");
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isFalse();

    // without any of the above conditions, the message should not be self-terminating
    // (multipart/byteranges is ignored, see note in method javadoc)
    httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
    isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
    assertThat(isResponseSelfTerminating).isFalse();
  }

  @Test
  public void testAddNewViaHeaderToExistingViaHeader() {
    HttpMessage httpMessage = new DefaultFullHttpRequest(HTTP_1_1, HttpMethod.GET, "/endpoint");
    httpMessage.headers().add(HttpHeaderNames.VIA, "1.1 otherproxy");
    ProxyUtils.addVia(httpMessage, "hostname");

    List<String> viaHeaders = httpMessage.headers().getAll(HttpHeaderNames.VIA);
    assertThat(viaHeaders).containsExactly("1.1 otherproxy", "1.1 hostname");
  }

  @Test
  @DisplayName("Incorrect header tokens")
  public void testSplitCommaSeparatedHeaderValues_incorrect_header_tokens() {
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues("one")).containsExactly("one");
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues("one,two,three"))
        .containsExactly("one", "two", "three");
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues("one, two, three"))
        .containsExactly("one", "two", "three");
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues(" one,two,  three "))
        .containsExactly("one", "two", "three");
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues("\t\tone ,\t two,  three\t"))
        .containsExactly("one", "two", "three");
  }

  @Test
  @DisplayName("Expected no header tokens")
  public void testSplitCommaSeparatedHeaderValues_expected_no_header_tokens() {
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues("")).isEmpty();
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues(",")).isEmpty();
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues(" ")).isEmpty();
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues("\t")).isEmpty();
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues("  \t  \t  ")).isEmpty();
    assertThat(ProxyUtils.splitCommaSeparatedHeaderValues(" ,  ,\t, ")).isEmpty();
  }

  /** Verifies that 'sdch' is removed from the 'Accept-Encoding' header list. */
  @Test
  public void testRemoveSdchEncoding() {
    final List<String> emptyList = new ArrayList<>();
    // Various cases where 'sdch' is not present within the accepted
    // encodings list
    assertRemoveSdchEncoding(singletonList(""), emptyList);
    assertRemoveSdchEncoding(singletonList("gzip"), singletonList("gzip"));

    assertRemoveSdchEncoding(
        Arrays.asList("gzip", "deflate", "br"), Arrays.asList("gzip", "deflate", "br"));
    assertRemoveSdchEncoding(
        singletonList("gzip, deflate, br"), singletonList("gzip, deflate, br"));

    // Various cases where 'sdch' is present within the accepted encodings
    // list
    assertRemoveSdchEncoding(singletonList("sdch"), emptyList);
    assertRemoveSdchEncoding(singletonList("SDCH"), emptyList);

    assertRemoveSdchEncoding(Arrays.asList("sdch", "gzip"), singletonList("gzip"));
    assertRemoveSdchEncoding(singletonList("sdch, gzip"), singletonList("gzip"));

    assertRemoveSdchEncoding(
        Arrays.asList("gzip", "sdch", "deflate"), Arrays.asList("gzip", "deflate"));
    assertRemoveSdchEncoding(singletonList("gzip, sdch, deflate"), singletonList("gzip, deflate"));
    assertRemoveSdchEncoding(singletonList("gzip,deflate,sdch"), singletonList("gzip,deflate"));

    assertRemoveSdchEncoding(
        Arrays.asList("gzip", "deflate, sdch", "br"), Arrays.asList("gzip", "deflate", "br"));
  }

  /**
   * Helper method that asserts that 'sdch' is removed from the 'Accept-Encoding' header.
   *
   * @param inputEncodings The input list that maps to the values of the 'Accept-Encoding' header
   *     that should be used as the basis for the assertion check.
   * @param expectedEncodings The list containing the expected values of the 'Accept-Encoding'
   *     header after the 'sdch' encoding is removed.
   */
  private void assertRemoveSdchEncoding(
      List<String> inputEncodings, List<String> expectedEncodings) {
    HttpHeaders headers = new DefaultHttpHeaders();

    for (String encoding : inputEncodings) {
      headers.add(HttpHeaderNames.ACCEPT_ENCODING, encoding);
    }

    ProxyUtils.removeSdchEncoding(headers);
    assertThat(headers.getAll(ACCEPT_ENCODING)).isEqualTo(expectedEncodings);
  }
}
