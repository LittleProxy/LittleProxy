package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.littleshoot.proxy.FlowContext;

/**
 * Represents an exclusion pattern for response headers. This field logs all response headers EXCEPT
 * those matching the given regex pattern. Useful for excluding sensitive headers.
 */
public class ExcludeResponseHeaderField implements LogField {

  private final Pattern excludePattern;
  private final Function<String, String> fieldNameTransformer;
  private final Function<String, String> valueTransformer;

  public ExcludeResponseHeaderField(String excludeRegex) {
    this(excludeRegex, ExcludeResponseHeaderField::defaultFieldName, value -> value);
  }

  public ExcludeResponseHeaderField(
      String excludeRegex,
      Function<String, String> fieldNameTransformer,
      Function<String, String> valueTransformer) {
    this.excludePattern = Pattern.compile(excludeRegex);
    this.fieldNameTransformer =
        fieldNameTransformer != null
            ? fieldNameTransformer
            : ExcludeResponseHeaderField::defaultFieldName;
    this.valueTransformer = valueTransformer != null ? valueTransformer : value -> value;
  }

  @Override
  public String getName() {
    return "res_all_except_" + excludePattern.pattern().toLowerCase().replaceAll("[^a-z0-9]", "_");
  }

  @Override
  public String getDescription() {
    return "Response headers excluding pattern: " + excludePattern.pattern();
  }

  @Override
  public String extractValue(FlowContext flowContext, HttpRequest request, HttpResponse response) {
    return "-";
  }

  public Map<String, String> extractMatchingHeaders(HttpHeaders headers) {
    Map<String, String> matches = new TreeMap<>();
    for (String headerName : headers.names()) {
      if (!excludePattern.matcher(headerName).matches()) {
        String value = headers.get(headerName);
        String fieldName = fieldNameTransformer.apply(headerName);
        matches.put(fieldName, value != null ? valueTransformer.apply(value) : "-");
      }
    }
    return matches;
  }

  private static String defaultFieldName(String headerName) {
    return "res_" + headerName.toLowerCase().replaceAll("[^a-z0-9]", "_");
  }
}
