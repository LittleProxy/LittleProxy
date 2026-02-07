package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.littleshoot.proxy.FlowContext;

/**
 * Enumeration of standard log fields that are commonly used in HTTP access logs. These fields have
 * predefined extraction logic and represent the most common logging data points.
 */
public enum StandardField implements LogField {
  FLOW_ID("flow_id", "Unique flow identifier (ULID) for tracing requests across the proxy"),
  TIMESTAMP("timestamp", "Timestamp when the request was processed"),
  CLIENT_IP("client_ip", "IP address of the client making the request"),
  REMOTE_IP("remote_ip", "Remote IP address (from X-Forwarded-For or X-Real-IP)"),
  METHOD("method", "HTTP method (GET, POST, etc.)"),
  URI("uri", "Request URI or full URL"),
  STATUS("status", "HTTP response status code"),
  BYTES("bytes", "Response content length in bytes"),
  HTTP_REQUEST_PROCESSING_TIME_MS(
      "http_request_processing_time_ms",
      "HTTP request processing duration from receipt to response (milliseconds)"),
  REFERER("referer", "Referer header from the request"),
  USER_AGENT("user_agent", "User-Agent header from the request"),
  PROTOCOL("protocol", "HTTP protocol version"),
  TCP_CONNECTION_ESTABLISHMENT_TIME_MS(
      "tcp_connection_establishment_time_ms",
      "Time to establish TCP connection to server in milliseconds"),
  TCP_CLIENT_CONNECTION_DURATION_MS(
      "tcp_client_connection_duration_ms",
      "Total client TCP connection lifetime from connect to disconnect (includes pre-request time, milliseconds)"),
  TCP_SERVER_CONNECTION_DURATION_MS(
      "tcp_server_connection_duration_ms", "Total server TCP connection duration in milliseconds"),
  SSL_HANDSHAKE_TIME_MS("ssl_handshake_time_ms", "SSL handshake duration in milliseconds"),
  SATURATION_COUNT("saturation_count", "Number of times connection became saturated"),
  EXCEPTION_TYPE("exception_type", "Type of exception if occurred");

  private final String name;
  private final String description;

  StandardField(String name, String description) {
    this.name = name;
    this.description = description;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String extractValue(FlowContext flowContext, HttpRequest request, HttpResponse response) {
    switch (this) {
      case TIMESTAMP:
        return ZonedDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

      case CLIENT_IP:
        InetSocketAddress clientAddress = flowContext.getClientAddress();
        return clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";

      case REMOTE_IP:
        return extractRemoteIp(request, flowContext);

      case METHOD:
        return request.method().name();

      case URI:
        return extractFullUrl(request);

      case STATUS:
        return String.valueOf(response.status().code());

      case BYTES:
        String contentLength = response.headers().get("Content-Length");
        return contentLength != null ? contentLength : "-";

      case HTTP_REQUEST_PROCESSING_TIME_MS:
        Long httpRequestProcessingTime =
            flowContext.getTimingData("http_request_processing_time_ms");
        return httpRequestProcessingTime != null ? String.valueOf(httpRequestProcessingTime) : "-";

      case REFERER:
        String referer = request.headers().get("Referer");
        return referer != null ? referer : "-";

      case USER_AGENT:
        String userAgent = request.headers().get("User-Agent");
        return userAgent != null ? userAgent : "-";

      case PROTOCOL:
        return request.protocolVersion().text();

      case TCP_CONNECTION_ESTABLISHMENT_TIME_MS:
        Long tcpConnectionEstablishmentTime =
            flowContext.getTimingData("tcp_connection_establishment_time_ms");
        return tcpConnectionEstablishmentTime != null
            ? String.valueOf(tcpConnectionEstablishmentTime)
            : "-";

      case TCP_CLIENT_CONNECTION_DURATION_MS:
        Long tcpClientConnectionDuration =
            flowContext.getTimingData("tcp_client_connection_duration_ms");
        return tcpClientConnectionDuration != null
            ? String.valueOf(tcpClientConnectionDuration)
            : "-";

      case TCP_SERVER_CONNECTION_DURATION_MS:
        Long tcpServerConnectionDuration =
            flowContext.getTimingData("tcp_server_connection_duration_ms");
        return tcpServerConnectionDuration != null
            ? String.valueOf(tcpServerConnectionDuration)
            : "-";

      case SSL_HANDSHAKE_TIME_MS:
        Long sslHandshakeTime = flowContext.getTimingData("ssl_handshake_time_ms");
        return sslHandshakeTime != null ? String.valueOf(sslHandshakeTime) : "-";

      case SATURATION_COUNT:
        return "-";

      case EXCEPTION_TYPE:
        return "-";

      default:
        return "-";
    }
  }

  private String extractRemoteIp(HttpRequest request, FlowContext flowContext) {
    // Check X-Forwarded-For header first
    String xForwardedFor = request.headers().get("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
      // Take the first IP in the chain
      return xForwardedFor.split(",")[0].trim();
    }

    // Check X-Real-IP header next
    String xRealIp = request.headers().get("X-Real-IP");
    if (xRealIp != null && !xRealIp.isEmpty()) {
      return xRealIp;
    }

    // Fallback to client address
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    return clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
  }

  private String extractFullUrl(HttpRequest request) {
    String uri = request.uri();

    // Check if URI is already absolute (contains scheme)
    if (uri.startsWith("http://") || uri.startsWith("https://")) {
      return uri;
    }

    // For CONNECT requests, the URI is just host:port
    if (request.method().name().equals("CONNECT")) {
      return uri;
    }

    // Get host from Host header
    String host = request.headers().get("Host");
    if (host == null || host.isEmpty()) {
      return uri;
    }

    // Determine scheme (default to http)
    String scheme = "http";

    // Reconstruct full URL
    if (uri.startsWith("/")) {
      return scheme + "://" + host + uri;
    } else {
      return scheme + "://" + host + "/" + uri;
    }
  }
}
