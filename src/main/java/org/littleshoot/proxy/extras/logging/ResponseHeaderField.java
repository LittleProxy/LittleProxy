package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Objects;
import org.littleshoot.proxy.FlowContext;

/**
 * Represents a response header field that should be logged. This class allows logging any custom
 * header from HTTP responses.
 */
public class ResponseHeaderField implements LogField {

  private final String headerName;
  private final String fieldName;
  private final String description;

  /**
   * Creates a new response header field.
   *
   * @param headerName the name of the HTTP header to extract
   */
  public ResponseHeaderField(String headerName) {
    this.headerName = Objects.requireNonNull(headerName, "headerName must not be null");
    if (this.headerName.isBlank()) {
      throw new IllegalArgumentException("headerName must not be blank");
    }
    this.fieldName = "resp_" + this.headerName.toLowerCase().replaceAll("[^a-z0-9]", "_");
    this.description = "Response header: " + this.headerName;
  }

  /**
   * Creates a new response header field with custom field name.
   *
   * @param headerName the name of the HTTP header to extract
   * @param fieldName the name to use for this field in logs
   */
  public ResponseHeaderField(String headerName, String fieldName) {
    this.headerName = Objects.requireNonNull(headerName, "headerName must not be null");
    this.fieldName = Objects.requireNonNull(fieldName, "fieldName must not be null");
    if (this.headerName.isBlank() || this.fieldName.isBlank()) {
      throw new IllegalArgumentException("headerName/fieldName must not be blank");
    }
    this.description = "Response header: " + this.headerName;
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
    if (response == null || response.headers() == null) {
      return "-";
    }
    String value = response.headers().get(headerName);
    return value != null ? value : "-";
  }

  public String getHeaderName() {
    return headerName;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    ResponseHeaderField that = (ResponseHeaderField) obj;
    return headerName.equals(that.headerName);
  }

  @Override
  public int hashCode() {
    return headerName.hashCode();
  }

  @Override
  public String toString() {
    return "ResponseHeaderField{headerName='" + headerName + "', fieldName='" + fieldName + "'}";
  }
}
