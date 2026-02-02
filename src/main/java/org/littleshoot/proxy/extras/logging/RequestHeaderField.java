package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.littleshoot.proxy.FlowContext;

/**
 * Represents a request header field that should be logged. This class allows logging any custom
 * header from HTTP requests.
 */
public class RequestHeaderField implements LogField {

  private final String headerName;
  private final String fieldName;
  private final String description;

  /**
   * Creates a new request header field.
   *
   * @param headerName the name of the HTTP header to extract
   */
  public RequestHeaderField(String headerName) {
    this.headerName = headerName;
    this.fieldName = "req_" + headerName.toLowerCase().replaceAll("[^a-z0-9]", "_");
    this.description = "Request header: " + headerName;
  }

  /**
   * Creates a new request header field with custom field name.
   *
   * @param headerName the name of the HTTP header to extract
   * @param fieldName the name to use for this field in logs
   */
  public RequestHeaderField(String headerName, String fieldName) {
    this.headerName = headerName;
    this.fieldName = fieldName;
    this.description = "Request header: " + headerName;
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
  public String extractValue(
      FlowContext flowContext, HttpRequest request, HttpResponse response, long duration) {
    String value = request.headers().get(headerName);
    return value != null ? value : "-";
  }

  public String getHeaderName() {
    return headerName;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    RequestHeaderField that = (RequestHeaderField) obj;
    return headerName.equals(that.headerName);
  }

  @Override
  public int hashCode() {
    return headerName.hashCode();
  }

  @Override
  public String toString() {
    return "RequestHeaderField{headerName='" + headerName + "', fieldName='" + fieldName + "'}";
  }
}
