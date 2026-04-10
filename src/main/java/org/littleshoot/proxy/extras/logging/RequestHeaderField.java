package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.littleshoot.proxy.FlowContext;

/**
 * Represents a request header field that should be logged. This class allows logging any custom
 * header from HTTP requests.
 */
public class RequestHeaderField implements LogField {

  private final String headerName;
  private final String fieldName;
  private final String description;
  private final Function<String, String> valueTransformer;

  public RequestHeaderField(String headerName) {
    this(headerName, null, null);
  }

  public RequestHeaderField(String headerName, String fieldName) {
    this(headerName, fieldName, null);
  }

  public RequestHeaderField(
      String headerName, String fieldName, Function<String, String> valueTransformer) {
    this.headerName = Objects.requireNonNull(headerName, "headerName must not be null");
    this.fieldName =
        fieldName != null
            ? fieldName
            : "req_" + this.headerName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    if (this.headerName.isBlank() || this.fieldName.isBlank()) {
      throw new IllegalArgumentException("headerName/fieldName must not be blank");
    }
    this.description = "Request header: " + this.headerName;
    this.valueTransformer = valueTransformer != null ? valueTransformer : (v -> v);
  }

  @Override
  public String getName() {
    return fieldName;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String extractValue(FlowContext flowContext, HttpRequest request, HttpResponse response) {
    if (request == null || request.headers() == null) {
      return "-";
    }
    String value = request.headers().get(headerName);
    return value != null ? valueTransformer.apply(value) : "-";
  }

  public String getHeaderName() {
    return headerName;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    RequestHeaderField that = (RequestHeaderField) obj;
    return headerName.equals(that.headerName)
        && fieldName.equals(that.fieldName)
        && Objects.equals(valueTransformer, that.valueTransformer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(headerName, fieldName, valueTransformer);
  }

  @Override
  public String toString() {
    return "RequestHeaderField{headerName='" + headerName + "', fieldName='" + fieldName + "'}";
  }
}
