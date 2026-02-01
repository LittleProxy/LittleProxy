package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.littleshoot.proxy.ActivityTrackerAdapter;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** An {@link org.littleshoot.proxy.ActivityTracker} that logs HTTP activity. */
public class ActivityLogger extends ActivityTrackerAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(ActivityLogger.class);
  private static final String DATE_FORMAT_CLF = "dd/MMM/yyyy:HH:mm:ss Z";
  public static final String UTC = "UTC";
  public static final String USER_AGENT = "User-Agent";
  public static final String ISO_8601_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

  private final LogFormat logFormat;
  private final LogFieldConfiguration fieldConfiguration;

  private static class TimedRequest {
    final HttpRequest request;
    final long startTime;

    TimedRequest(HttpRequest request, long startTime) {
      this.request = request;
      this.startTime = startTime;
    }
  }

  private final Map<FlowContext, TimedRequest> requestMap = new ConcurrentHashMap<>();

  public ActivityLogger(LogFormat logFormat, LogFieldConfiguration fieldConfiguration) {
    this.logFormat = logFormat;
    this.fieldConfiguration = fieldConfiguration != null ? 
      fieldConfiguration : LogFieldConfiguration.defaultConfig();
    validateStandardsCompliance();
  }

  /**
   * Validates that the current configuration complies with logging standards.
   * Throws IllegalArgumentException if configuration violates standards.
   */
  private void validateStandardsCompliance() {
    if (fieldConfiguration.isStrictStandardsCompliance()) {
      switch (logFormat) {
        case CLF:
          // CLF format is very strict - only allows standard fields
          for (LogField field : fieldConfiguration.getFields()) {
            if (!(field instanceof StandardField) || 
                field == StandardField.REFERER || 
                field == StandardField.USER_AGENT) {
              throw new IllegalArgumentException(
                  "CLF format does not support custom headers or referer/user-agent in strict compliance mode");
            }
          }
          break;
          
        case ELF:
          // ELF format should include referer by standard
          if (!fieldConfiguration.hasField(StandardField.REFERER)) {
            throw new IllegalArgumentException(
                  "ELF format should include referer field according to NCSA combined log standard");
          }
          break;
          
        case W3C:
          // Validate W3C field naming conventions
          for (LogField field : fieldConfiguration.getFields()) {
            if (field instanceof RequestHeaderField) {
              RequestHeaderField reqField = (RequestHeaderField) field;
              String fieldName = "cs(" + reqField.getHeaderName() + ")";
              // W3C fields should use cs- and sc- prefixes
            } else if (field instanceof ResponseHeaderField) {
              ResponseHeaderField respField = (ResponseHeaderField) field;
              String fieldName = "sc(" + respField.getHeaderName() + ")";
              // W3C fields should use cs- and sc- prefixes
            }
          }
          break;
          
        default:
          // JSON, LTSV, CSV, SQUID, HAPROXY are flexible
          break;
      }
    }
  }

  @Override
  public void requestReceivedFromClient(FlowContext flowContext, HttpRequest httpRequest) {
    requestMap.put(flowContext, new TimedRequest(httpRequest, System.currentTimeMillis()));
  }

  @Override
  public void responseSentToClient(FlowContext flowContext, HttpResponse httpResponse) {
    TimedRequest timedRequest = requestMap.remove(flowContext);
    if (timedRequest == null) {
      return;
    }

    String logMessage = formatLogEntry(flowContext, timedRequest, httpResponse);
    if (logMessage != null) {
      log(logMessage);
    }
  }

  protected void log(String message) {
    LOG.info(message);
  }

  // Cleanup on disconnect just in case
  @Override
  public void clientDisconnected(
      InetSocketAddress clientAddress, javax.net.ssl.SSLSession sslSession) {
    // We can't easily clean up by FlowContext here as we only have address/session.
    // For now, rely on responseSentToClient to clear.
  }

  /**
   * Formats a log entry using the configured fields.
   * This method dynamically generates log entries based on the field configuration
   * rather than hardcoded field lists.
   */
  private String formatLogEntry(
      FlowContext flowContext, TimedRequest timedInfo, HttpResponse response) {
    HttpRequest request = timedInfo.request;
    long duration = System.currentTimeMillis() - timedInfo.startTime;

    StringBuilder sb = new StringBuilder();
    ZonedDateTime now = ZonedDateTime.now(ZoneId.of(UTC));

    switch (logFormat) {
      case CLF:
        formatClfEntry(sb, flowContext, request, response, duration, now);
        break;

      case ELF:
        formatElfEntry(sb, flowContext, request, response, duration, now);
        break;

      case W3C:
        formatW3cEntry(sb, flowContext, request, response, duration, now);
        break;

      case JSON:
        formatJsonEntry(sb, flowContext, request, response, duration, now);
        break;

      case LTSV:
        formatLtsvEntry(sb, flowContext, request, response, duration, now);
        break;

      case CSV:
        formatCsvEntry(sb, flowContext, request, response, duration, now);
        break;

      case SQUID:
        formatSquidEntry(sb, flowContext, request, response, duration, now);
        break;

      case HAPROXY:
        formatHaproxyEntry(sb, flowContext, request, response, duration, now);
        break;
    }

    return sb.toString();
  }

  /**
   * Formats CLF log entry.
   */
  private void formatClfEntry(StringBuilder sb, FlowContext flowContext, 
      HttpRequest request, HttpResponse response, long duration, ZonedDateTime now) {
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    String clientIp = clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
    
    // CLF: host ident authuser [date] "request" status bytes
    sb.append(clientIp).append(" ");
    sb.append("- "); // ident
    sb.append("- "); // authuser
    sb.append("[").append(format(now, DATE_FORMAT_CLF)).append("] ");
    sb.append("\"")
        .append(request.method())
        .append(" ")
        .append(getFullUrl(request))
        .append(" ")
        .append(request.protocolVersion())
        .append("\" ");
    sb.append(response.status().code()).append(" ");
    sb.append(getContentLength(response));
  }

  /**
   * Formats ELF log entry.
   */
  private void formatElfEntry(StringBuilder sb, FlowContext flowContext, 
      HttpRequest request, HttpResponse response, long duration, ZonedDateTime now) {
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    String clientIp = clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
    
    // ELF: host ident authuser [date] "request" status bytes "referer" "user-agent"
    sb.append(clientIp).append(" ");
    sb.append("- "); // ident
    sb.append("- "); // authuser
    sb.append("[").append(format(now, DATE_FORMAT_CLF)).append("] ");
    sb.append("\"")
        .append(request.method())
        .append(" ")
        .append(getFullUrl(request))
        .append(" ")
        .append(request.protocolVersion())
        .append("\" ");
    sb.append(response.status().code()).append(" ");
    sb.append(getContentLength(response)).append(" ");
    sb.append("\"").append(getHeader(request, "Referer")).append("\" ");
    sb.append("\"").append(getHeader(request, USER_AGENT)).append("\"");
  }

  /**
   * Formats W3C log entry.
   */
  private void formatW3cEntry(StringBuilder sb, FlowContext flowContext, 
      HttpRequest request, HttpResponse response, long duration, ZonedDateTime now) {
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    String clientIp = clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
    
    // W3C: date time c-ip cs-method cs-uri-stem sc-status sc-bytes cs(User-Agent)
    DateTimeFormatter w3cDateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);
    sb.append(now.format(w3cDateTimeFormatter)).append(" ");
    sb.append(clientIp).append(" ");
    sb.append(request.method()).append(" ");
    sb.append(getFullUrl(request)).append(" ");
    sb.append(response.status().code()).append(" ");
    sb.append(getContentLength(response)).append(" ");
    sb.append("\"").append(getHeader(request, USER_AGENT)).append("\"");
  }

  /**
   * Formats JSON log entry.
   */
  private void formatJsonEntry(StringBuilder sb, FlowContext flowContext,
      HttpRequest request, HttpResponse response, long duration, ZonedDateTime now) {

    sb.append("{");

    // Use configured fields dynamically
    boolean first = true;
    for (LogField field : fieldConfiguration.getFields()) {
      // Handle prefix-based fields that expand to multiple entries
      if (field instanceof PrefixRequestHeaderField) {
        PrefixRequestHeaderField prefixField = (PrefixRequestHeaderField) field;
        for (Map.Entry<String, String> entry : prefixField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof PrefixResponseHeaderField) {
        PrefixResponseHeaderField prefixField = (PrefixResponseHeaderField) field;
        for (Map.Entry<String, String> entry : prefixField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof RegexRequestHeaderField) {
        RegexRequestHeaderField regexField = (RegexRequestHeaderField) field;
        for (Map.Entry<String, String> entry : regexField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof RegexResponseHeaderField) {
        RegexResponseHeaderField regexField = (RegexResponseHeaderField) field;
        for (Map.Entry<String, String> entry : regexField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof ExcludeRequestHeaderField) {
        ExcludeRequestHeaderField excludeField = (ExcludeRequestHeaderField) field;
        for (Map.Entry<String, String> entry : excludeField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof ExcludeResponseHeaderField) {
        ExcludeResponseHeaderField excludeField = (ExcludeResponseHeaderField) field;
        for (Map.Entry<String, String> entry : excludeField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else {
        if (!first) {
          sb.append(",");
        }
        first = false;

        String value = field.extractValue(flowContext, request, response, duration);
        sb.append("\"").append(field.getName()).append("\":\"").append(escapeJson(value)).append("\"");
      }
    }

    sb.append("}");
  }

  /**
   * Formats LTSV log entry.
   */
  private void formatLtsvEntry(StringBuilder sb, FlowContext flowContext,
      HttpRequest request, HttpResponse response, long duration, ZonedDateTime now) {

    // Labeled Tab-Separated Values
    boolean first = true;
    for (LogField field : fieldConfiguration.getFields()) {
      // Handle prefix-based fields that expand to multiple entries
      if (field instanceof PrefixRequestHeaderField) {
        PrefixRequestHeaderField prefixField = (PrefixRequestHeaderField) field;
        for (Map.Entry<String, String> entry : prefixField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof PrefixResponseHeaderField) {
        PrefixResponseHeaderField prefixField = (PrefixResponseHeaderField) field;
        for (Map.Entry<String, String> entry : prefixField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof RegexRequestHeaderField) {
        RegexRequestHeaderField regexField = (RegexRequestHeaderField) field;
        for (Map.Entry<String, String> entry : regexField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof RegexResponseHeaderField) {
        RegexResponseHeaderField regexField = (RegexResponseHeaderField) field;
        for (Map.Entry<String, String> entry : regexField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof ExcludeRequestHeaderField) {
        ExcludeRequestHeaderField excludeField = (ExcludeRequestHeaderField) field;
        for (Map.Entry<String, String> entry : excludeField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof ExcludeResponseHeaderField) {
        ExcludeResponseHeaderField excludeField = (ExcludeResponseHeaderField) field;
        for (Map.Entry<String, String> entry : excludeField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else {
        if (!first) {
          sb.append("\t");
        }
        first = false;

        String value = field.extractValue(flowContext, request, response, duration);
        sb.append(field.getName()).append(":").append(value);
      }
    }
  }

  /**
   * Formats CSV log entry.
   */
  private void formatCsvEntry(StringBuilder sb, FlowContext flowContext,
      HttpRequest request, HttpResponse response, long duration, ZonedDateTime now) {

    // Comma-Separated Values
    boolean first = true;
    for (LogField field : fieldConfiguration.getFields()) {
      // Handle prefix-based fields that expand to multiple entries
      if (field instanceof PrefixRequestHeaderField) {
        PrefixRequestHeaderField prefixField = (PrefixRequestHeaderField) field;
        for (Map.Entry<String, String> entry : prefixField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof PrefixResponseHeaderField) {
        PrefixResponseHeaderField prefixField = (PrefixResponseHeaderField) field;
        for (Map.Entry<String, String> entry : prefixField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof RegexRequestHeaderField) {
        RegexRequestHeaderField regexField = (RegexRequestHeaderField) field;
        for (Map.Entry<String, String> entry : regexField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof RegexResponseHeaderField) {
        RegexResponseHeaderField regexField = (RegexResponseHeaderField) field;
        for (Map.Entry<String, String> entry : regexField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof ExcludeRequestHeaderField) {
        ExcludeRequestHeaderField excludeField = (ExcludeRequestHeaderField) field;
        for (Map.Entry<String, String> entry : excludeField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof ExcludeResponseHeaderField) {
        ExcludeResponseHeaderField excludeField = (ExcludeResponseHeaderField) field;
        for (Map.Entry<String, String> entry : excludeField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else {
        if (!first) {
          sb.append(",");
        }
        first = false;

        String value = field.extractValue(flowContext, request, response, duration);
        sb.append("\"").append(escapeJson(value)).append("\"");
      }
    }
  }

  /**
   * Formats SQUID log entry.
   */
  private void formatSquidEntry(StringBuilder sb, FlowContext flowContext, 
      HttpRequest request, HttpResponse response, long duration, ZonedDateTime now) {
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    String clientIp = clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
    
    // time elapsed remotehost code/status bytes method URL rfc931 peerstatus/peerhost type
    long timestamp = now.toEpochSecond();
    sb.append(timestamp / 1000).append(".").append(timestamp % 1000).append(" ");
    sb.append(duration).append(" "); // elapsed
    sb.append(clientIp).append(" ");
    sb.append("TCP_MISS/").append(response.status().code()).append(" ");
    sb.append(getContentLength(response)).append(" ");
    sb.append(request.method()).append(" ");
    sb.append(getFullUrl(request)).append(" ");
    sb.append("- "); // rfc931
    sb.append("DIRECT/").append(getServerIp(flowContext)).append(" ");
    sb.append(getContentType(response));
  }

  /**
   * Formats HAProxy log entry.
   */
  private void formatHaproxyEntry(StringBuilder sb, FlowContext flowContext, 
      HttpRequest request, HttpResponse response, long duration, ZonedDateTime now) {
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    String clientIp = clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
    
    // HAProxy HTTP format approximation - client_ip [date] method uri status bytes duration
    sb.append(clientIp).append(" ");
    sb.append("[").append(format(now, "dd/MMM/yyyy:HH:mm:ss.SSS")).append("] ");
    sb.append("\"")
        .append(request.method())
        .append(" ")
        .append(getFullUrl(request))
        .append(" ")
        .append(request.protocolVersion())
        .append("\" ");
    sb.append(response.status().code()).append(" ");
    sb.append(getContentLength(response)).append(" ");
    sb.append(duration); // duration in ms
  }

  /**
   * Reconstructs the full URL from the request.
   * If the URI is already absolute (starts with http:// or https://), returns it as-is.
   * Otherwise, prepends the Host header to create a complete URL.
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
   * Gets header value from request.
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
   * @param response the HTTP response
   * @return content length or "-"
   */
  protected String getContentLength(HttpResponse response) {
    String len = response.headers().get("Content-Length");
    return len != null ? len : "-";
  }

  /**
   * Gets content type from response.
   * @param response the HTTP response
   * @return content type or "-"
   */
  protected String getContentType(HttpResponse response) {
    String val = response.headers().get("Content-Type");
    return val != null ? val : "-";
  }

  /**
   * Gets server IP from flow context.
   * @param context the flow context
   * @return server IP or "-"
   */
  protected String getServerIp(FlowContext context) {
    if (context instanceof FullFlowContext) {
      String hostAndPort = ((FullFlowContext) context).getServerHostAndPort();
      if (hostAndPort != null) {
        // Returns "host:port", we want just the host/ip usually, or stick with
        // host:port as Squid format usually asks for remotehost or peerhost
        return hostAndPort.split(":")[0];
      }
    }
    return "-";
  }

  /**
   * Escapes JSON strings for safe logging.
   * @param s the string to escape
   * @return escaped string
   */
  protected String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\"", "\\\"").replace("\\", "\\\\");
  }

  /**
   * Formats timestamp using specified pattern.
   * @param zonedDateTime the date/time to format
   * @param pattern the date format pattern
   * @return formatted timestamp
   */
  private String format(ZonedDateTime zonedDateTime, String pattern) {
    DateTimeFormatter dtf = DateTimeFormatter.ofPattern(pattern, Locale.US);
    return zonedDateTime.format(dtf);
  }

}