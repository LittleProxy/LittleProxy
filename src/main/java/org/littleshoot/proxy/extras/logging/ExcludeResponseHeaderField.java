package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.littleshoot.proxy.FlowContext;

/**
 * Represents an exclusion pattern for response headers. This field logs all response headers EXCEPT
 * those matching the given regex pattern. Useful for excluding sensitive headers like Set-Cookie,
 * etc.
 */
public class ExcludeResponseHeaderField implements LogField {

  private final Pattern excludePattern;
  private final Function<String, String> fieldNameTransformer;
  private final Function<String, String> valueTransformer;
  private final String description;

  /**
   * Creates a new exclusion-based response header field.
   *
   * @param excludePattern the regex pattern for headers to exclude (e.g., "Set-Cookie")
   */
  public ExcludeResponseHeaderField(Pattern excludePattern) {
    this(excludePattern, name -> name.toLowerCase().replaceAll("[^a-z0-9]", "_"), value -> value);
  }

  /**
   * Creates a new exclusion-based response header field with custom transformers.
   *
   * @param excludePattern the regex pattern for headers to exclude
   * @param fieldNameTransformer function to transform header names to field names
   * @param valueTransformer function to transform header values
   */
  public ExcludeResponseHeaderField(
      Pattern excludePattern,
      Function<String, String> fieldNameTransformer,
      Function<String, String> valueTransformer) {
    this.excludePattern = excludePattern;
    this.fieldNameTransformer =
        fieldNameTransformer != null
            ? fieldNameTransformer
            : name -> name.toLowerCase().replaceAll("[^a-z0-9]", "_");
    this.valueTransformer = valueTransformer != null ? valueTransformer : value -> value;
    this.description = "Response headers excluding pattern: " + excludePattern.pattern();
  }

  /**
   * Creates a new exclusion-based response header field from a regex string.
   *
   * @param excludeRegex the regex pattern string for headers to exclude
   */
  public ExcludeResponseHeaderField(String excludeRegex) {
    this(Pattern.compile(excludeRegex));
  }

  /**
   * Creates a new exclusion-based response header field from a regex string with custom
   * transformers.
   *
   * @param excludeRegex the regex pattern string for headers to exclude
   * @param fieldNameTransformer function to transform header names to field names
   * @param valueTransformer function to transform header values
   */
  public ExcludeResponseHeaderField(
      String excludeRegex,
      Function<String, String> fieldNameTransformer,
      Function<String, String> valueTransformer) {
    this(Pattern.compile(excludeRegex), fieldNameTransformer, valueTransformer);
  }

  @Override
  public String getName() {
    return "resp_all_except_" + excludePattern.pattern().toLowerCase().replaceAll("[^a-z0-9]", "_");
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String extractValue(
      FlowContext flowContext, HttpRequest request, HttpResponse response, long duration) {
    return "-";
  }

  /**
   * Extracts all headers that do NOT match the exclude pattern.
   *
   * @param headers the HTTP headers to search
   * @return a map of field names to header values for all non-matching headers
   */
  public Map<String, String> extractMatchingHeaders(HttpHeaders headers) {
    Map<String, String> matches = new TreeMap<>();
    for (String headerName : headers.names()) {
      Matcher matcher = excludePattern.matcher(headerName);
      if (!matcher.matches()) {
        String value = headers.get(headerName);
        String fieldName = fieldNameTransformer.apply(headerName);
        String transformedValue = value != null ? valueTransformer.apply(value) : "-";
        matches.put(fieldName, transformedValue);
      }
    }
    return matches;
  }

  public Pattern getExcludePattern() {
    return excludePattern;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    ExcludeResponseHeaderField that = (ExcludeResponseHeaderField) obj;
    return excludePattern.pattern().equals(that.excludePattern.pattern());
  }

  @Override
  public int hashCode() {
    return excludePattern.pattern().hashCode();
  }

  @Override
  public String toString() {
    return "ExcludeResponseHeaderField{excludePattern='" + excludePattern.pattern() + "'}";
  }
}
