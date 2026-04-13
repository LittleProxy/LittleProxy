package org.littleshoot.proxy.extras.logging.formatter;

import com.google.common.net.HostAndPort;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import java.util.Map;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.extras.logging.ActivityLogger.TimedRequest;
import org.littleshoot.proxy.extras.logging.ExcludeRequestHeaderField;
import org.littleshoot.proxy.extras.logging.ExcludeResponseHeaderField;
import org.littleshoot.proxy.extras.logging.LogField;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;
import org.littleshoot.proxy.extras.logging.PrefixRequestHeaderField;
import org.littleshoot.proxy.extras.logging.PrefixResponseHeaderField;
import org.littleshoot.proxy.extras.logging.RegexRequestHeaderField;
import org.littleshoot.proxy.extras.logging.RegexResponseHeaderField;

/**
 * Formatter for Key-Value structured text format.
 *
 * <p>Produces human-readable key=value pairs with flow and connection metrics.
 *
 * <p>Example: flow_id=abc123 client_ip=127.0.0.1 client_port=12345 method=GET uri="/path"
 * status=200 bytes=1234
 */
public class KeyValueFormatter extends AbstractLogEntryFormatter {

  @Override
  public String format(
      FlowContext context,
      TimedRequest timedRequest,
      HttpResponse response,
      ZonedDateTime now,
      LogFieldConfiguration fieldConfig) {
    HttpRequest request = timedRequest.getRequest();
    String flowId = timedRequest.getFlowId();
    Map<String, Long> requestTimingData = timedRequest.getTimings();

    StringBuilder sb = new StringBuilder();
    sb.append("flow_id=").append(escapeKv(flowId));

    // If no custom fields configured, use default core fields for backward compatibility
    if (fieldConfig.getFields().isEmpty()) {
      InetSocketAddress clientAddress = context.getClientAddress();
      String clientIp =
          (clientAddress != null && clientAddress.getAddress() != null)
              ? clientAddress.getAddress().getHostAddress()
              : "-";
      int clientPort = clientAddress != null ? clientAddress.getPort() : 0;

      String serverAddress = "-";
      int serverPort = 0;
      if (context instanceof FullFlowContext) {
        String hostAndPort = ((FullFlowContext) context).getServerHostAndPort();
        if (hostAndPort != null) {
          try {
            HostAndPort parsed = HostAndPort.fromString(hostAndPort);
            serverAddress = parsed.getHost();
            serverPort = parsed.hasPort() ? parsed.getPort() : 0;
          } catch (IllegalArgumentException ignored) {
            serverAddress = "-";
            serverPort = 0;
          }
        }
      }

      String httpRequestMs = getTimingData(context, "http_request_processing_time_ms");

      sb.append(" client_ip=").append(clientIp);
      sb.append(" client_port=").append(clientPort);
      sb.append(" server_ip=").append(serverAddress);
      sb.append(" server_port=").append(serverPort);
      sb.append(" method=").append(request.method().name());
      sb.append(" uri=\"").append(escapeKv(request.uri())).append("\"");
      sb.append(" protocol=").append(request.protocolVersion());
      sb.append(" status=").append(response.status().code());
      sb.append(" bytes=").append(getContentLength(response));
      sb.append(" http_request_ms=").append(httpRequestMs);
    } else {
      // Use configured fields dynamically
      for (LogField field : fieldConfig.getFields()) {
        // Handle prefix-based fields that expand to multiple entries
        if (field instanceof PrefixRequestHeaderField) {
          if (request != null) {
            PrefixRequestHeaderField prefixField = (PrefixRequestHeaderField) field;
            for (Map.Entry<String, String> entry :
                prefixField.extractMatchingHeaders(request.headers()).entrySet()) {
              sb.append(" ");
              sb.append(escapeKvKey(entry.getKey()));
              sb.append("=");
              sb.append(quoteIfNeeded(escapeKv(entry.getValue())));
            }
          }
        } else if (field instanceof PrefixResponseHeaderField) {
          if (response != null) {
            PrefixResponseHeaderField prefixField = (PrefixResponseHeaderField) field;
            for (Map.Entry<String, String> entry :
                prefixField.extractMatchingHeaders(response.headers()).entrySet()) {
              sb.append(" ");
              sb.append(escapeKvKey(entry.getKey()));
              sb.append("=");
              sb.append(quoteIfNeeded(escapeKv(entry.getValue())));
            }
          }
        } else if (field instanceof RegexRequestHeaderField) {
          if (request != null) {
            RegexRequestHeaderField regexField = (RegexRequestHeaderField) field;
            for (Map.Entry<String, String> entry :
                regexField.extractMatchingHeaders(request.headers()).entrySet()) {
              sb.append(" ");
              sb.append(escapeKvKey(entry.getKey()));
              sb.append("=");
              sb.append(quoteIfNeeded(escapeKv(entry.getValue())));
            }
          }
        } else if (field instanceof RegexResponseHeaderField) {
          if (response != null) {
            RegexResponseHeaderField regexField = (RegexResponseHeaderField) field;
            for (Map.Entry<String, String> entry :
                regexField.extractMatchingHeaders(response.headers()).entrySet()) {
              sb.append(" ");
              sb.append(escapeKvKey(entry.getKey()));
              sb.append("=");
              sb.append(quoteIfNeeded(escapeKv(entry.getValue())));
            }
          }
        } else if (field instanceof ExcludeRequestHeaderField) {
          if (request != null) {
            ExcludeRequestHeaderField excludeField = (ExcludeRequestHeaderField) field;
            for (Map.Entry<String, String> entry :
                excludeField.extractMatchingHeaders(request.headers()).entrySet()) {
              sb.append(" ");
              sb.append(escapeKvKey(entry.getKey()));
              sb.append("=");
              sb.append(quoteIfNeeded(escapeKv(entry.getValue())));
            }
          }
        } else if (field instanceof ExcludeResponseHeaderField) {
          if (response != null) {
            ExcludeResponseHeaderField excludeField = (ExcludeResponseHeaderField) field;
            for (Map.Entry<String, String> entry :
                excludeField.extractMatchingHeaders(response.headers()).entrySet()) {
              sb.append(" ");
              sb.append(escapeKvKey(entry.getKey()));
              sb.append("=");
              sb.append(quoteIfNeeded(escapeKv(entry.getValue())));
            }
          }
        } else {
          String value = field.extractValue(context, request, response, requestTimingData);
          // Skip fields with null values (e.g., TCP timing data not yet available)
          if (value != null) {
            sb.append(" ");
            sb.append(escapeKvKey(field.getName()));
            sb.append("=");
            sb.append(quoteIfNeeded(escapeKv(value)));
          }
        }
      }
    }

    return sb.toString();
  }

  @Override
  public LogFormat getSupportedFormat() {
    return LogFormat.KEYVALUE;
  }

  @Override
  public String formatLifecycleEvent(
      LifecycleEvent event, FlowContext context, Map<String, Object> attributes, String flowId) {
    StringBuilder sb = new StringBuilder();
    sb.append("flow_id=").append(flowId);
    sb.append(" event=").append(event.getEventName());
    sb.append(" client_ip=").append(getClientIp(context));

    // Add all event-specific attributes
    if (attributes != null) {
      for (Map.Entry<String, Object> entry : attributes.entrySet()) {
        String key = escapeKvKey(entry.getKey());
        Object rawValue = entry.getValue();
        String value = rawValue == null ? null : rawValue.toString();

        sb.append(" ");
        sb.append(key);
        sb.append("=");
        sb.append(quoteIfNeeded(escapeKv(value)));
      }
    }

    return sb.toString();
  }

  private String escapeKvKey(String key) {
    if (key == null || key.isEmpty()) {
      return "-";
    }
    return key.replaceAll("[^A-Za-z0-9_.-]", "_");
  }

  private String escapeKv(String value) {
    if (value == null) {
      return "-";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private String quoteIfNeeded(String escaped) {
    if ("-".equals(escaped)) {
      return escaped;
    }
    return "\"" + escaped + "\"";
  }
}
