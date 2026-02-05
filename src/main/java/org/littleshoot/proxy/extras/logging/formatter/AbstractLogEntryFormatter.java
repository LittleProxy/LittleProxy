package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;

/**
 * Abstract base class for log entry formatters providing common utility methods. This class
 * extracts shared functionality used by all formatter implementations, such as URL reconstruction,
 * header extraction, and JSON escaping.
 */
public abstract class AbstractLogEntryFormatter implements LogEntryFormatter {

  /** Date format pattern for Common Log Format. */
  protected static final String DATE_FORMAT_CLF = "dd/MMM/yyyy:HH:mm:ss Z";

  /** UTC timezone identifier. */
  protected static final String UTC = "UTC";

  /** User-Agent header name. */
  protected static final String USER_AGENT = "User-Agent";

  /** ISO 8601 date/time pattern. */
  protected static final String ISO_8601_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

  /**
   * Reconstructs the full URL from the request. If the URI is already absolute (starts with http://
   * or https://), returns it as-is. Otherwise, prepends the Host header to create a complete URL.
   *
   * @param request the HTTP request
   * @return the full URL
   */
  protected String getFullUrl(HttpRequest request) {
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
      // Fallback: return URI as-is if no Host header
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

  /**
   * Extracts the URI stem (path) from the request for W3C format. Returns just the path component
   * without scheme and host.
   *
   * @param request the HTTP request
   * @return the URI stem (path)
   */
  protected String getUriStem(HttpRequest request) {
    String uri = request.uri();

    // If it's already a path (starts with /), return as-is
    if (uri.startsWith("/")) {
      return uri;
    }

    // If it's a full URL, extract just the path
    if (uri.startsWith("http://") || uri.startsWith("https://")) {
      int pathStart = uri.indexOf("/", uri.indexOf("://") + 3);
      if (pathStart == -1) {
        return "/";
      }
      int queryStart = uri.indexOf("?", pathStart);
      if (queryStart == -1) {
        return uri.substring(pathStart);
      } else {
        return uri.substring(pathStart, queryStart);
      }
    }

    // For CONNECT requests or other cases, return as-is
    return uri;
  }

  /**
   * Gets header value from request.
   *
   * @param request the HTTP request
   * @param headerName the header name
   * @return header value or "-"
   */
  protected String getHeader(HttpRequest request, String headerName) {
    String val = request.headers().get(headerName);
    return val != null ? val : "-";
  }

  /**
   * Gets content length from response.
   *
   * @param response the HTTP response
   * @return content length or "-"
   */
  protected String getContentLength(HttpResponse response) {
    String len = response.headers().get("Content-Length");
    return len != null ? len : "-";
  }

  /**
   * Gets content type from response.
   *
   * @param response the HTTP response
   * @return content type or "-"
   */
  protected String getContentType(HttpResponse response) {
    String val = response.headers().get("Content-Type");
    return val != null ? val : "-";
  }

  /**
   * Gets server IP from flow context.
   *
   * @param context the flow context
   * @return server IP or "-"
   */
  protected String getServerIp(FlowContext context) {
    if (context instanceof FullFlowContext) {
      String hostAndPort = ((FullFlowContext) context).getServerHostAndPort();
      if (hostAndPort != null) {
        // Returns "host:port", we want just the host/ip usually
        return hostAndPort.split(":")[0];
      }
    }
    return "-";
  }

  /**
   * Gets client IP address from flow context.
   *
   * @param context the flow context
   * @return client IP or "-"
   */
  protected String getClientIp(FlowContext context) {
    if (context.getClientAddress() != null) {
      return context.getClientAddress().getAddress().getHostAddress();
    }
    return "-";
  }

  /**
   * Gets client port from flow context.
   *
   * @param context the flow context
   * @return client port or 0
   */
  protected int getClientPort(FlowContext context) {
    if (context.getClientAddress() != null) {
      return context.getClientAddress().getPort();
    }
    return 0;
  }

  /**
   * Escapes JSON strings for safe logging.
   *
   * @param s the string to escape
   * @return escaped string
   */
  protected String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\"", "\\\"").replace("\\", "\\\\");
  }

  /**
   * Formats timestamp using specified pattern.
   *
   * @param zonedDateTime the date/time to format
   * @param pattern the date format pattern
   * @return formatted timestamp
   */
  protected String format(ZonedDateTime zonedDateTime, String pattern) {
    DateTimeFormatter dtf = DateTimeFormatter.ofPattern(pattern, Locale.US);
    return zonedDateTime.format(dtf);
  }
}
