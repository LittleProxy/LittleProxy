package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.Map;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.*;

/**
 * Formatter for Labeled Tab-Separated Values (LTSV) format.
 *
 * <p>LTSV format uses label:value pairs separated by tabs.
 *
 * <p>Example: flow_id:abc123 client_ip:127.0.0.1 method:GET status:200
 */
public class LtsvFormatter extends AbstractLogEntryFormatter {

  @Override
  public String format(
      FlowContext context,
      HttpRequest request,
      HttpResponse response,
      ZonedDateTime now,
      String flowId,
      LogFieldConfiguration fieldConfig) {

    StringBuilder sb = new StringBuilder();

    // Labeled Tab-Separated Values
    sb.append("flow_id:").append(sanitizeLtsv(flowId));
    for (LogField field : fieldConfig.getFields()) {
      // Handle prefix-based fields that expand to multiple entries
      if (field instanceof PrefixRequestHeaderField) {
        if (request == null) {
          continue;
        }
        PrefixRequestHeaderField prefixField = (PrefixRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            prefixField.extractMatchingHeaders(request.headers()).entrySet()) {
          sb.append("\t");
          sb.append(sanitizeLtsvLabel(entry.getKey()))
              .append(":")
              .append(sanitizeLtsv(entry.getValue()));
        }
      } else if (field instanceof PrefixResponseHeaderField) {
        if (response == null) {
          continue;
        }
        PrefixResponseHeaderField prefixField = (PrefixResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            prefixField.extractMatchingHeaders(response.headers()).entrySet()) {
          sb.append("\t");
          sb.append(sanitizeLtsvLabel(entry.getKey()))
              .append(":")
              .append(sanitizeLtsv(entry.getValue()));
        }
      } else if (field instanceof RegexRequestHeaderField) {
        if (request == null) {
          continue;
        }
        RegexRequestHeaderField regexField = (RegexRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            regexField.extractMatchingHeaders(request.headers()).entrySet()) {
          sb.append("\t");
          sb.append(sanitizeLtsvLabel(entry.getKey()))
              .append(":")
              .append(sanitizeLtsv(entry.getValue()));
        }
      } else if (field instanceof RegexResponseHeaderField) {
        if (response == null) {
          continue;
        }
        RegexResponseHeaderField regexField = (RegexResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            regexField.extractMatchingHeaders(response.headers()).entrySet()) {
          sb.append("\t");
          sb.append(sanitizeLtsvLabel(entry.getKey()))
              .append(":")
              .append(sanitizeLtsv(entry.getValue()));
        }
      } else if (field instanceof ExcludeRequestHeaderField) {
        if (request == null) {
          continue;
        }
        ExcludeRequestHeaderField excludeField = (ExcludeRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            excludeField.extractMatchingHeaders(request.headers()).entrySet()) {
          sb.append("\t");
          sb.append(sanitizeLtsvLabel(entry.getKey()))
              .append(":")
              .append(sanitizeLtsv(entry.getValue()));
        }
      } else if (field instanceof ExcludeResponseHeaderField) {
        if (response == null) {
          continue;
        }
        ExcludeResponseHeaderField excludeField = (ExcludeResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            excludeField.extractMatchingHeaders(response.headers()).entrySet()) {
          sb.append("\t");
          sb.append(sanitizeLtsvLabel(entry.getKey()))
              .append(":")
              .append(sanitizeLtsv(entry.getValue()));
        }
      } else {
        String value = field.extractValue(context, request, response);
        // Skip fields with null values (e.g., TCP timing data not yet available)
        if (value != null) {
          sb.append("\t");
          sb.append(sanitizeLtsvLabel(field.getName())).append(":").append(sanitizeLtsv(value));
        }
      }
    }

    return sb.toString();
  }

  @Override
  public LogFormat getSupportedFormat() {
    return LogFormat.LTSV;
  }

  @Override
  public String formatLifecycleEvent(
      LifecycleEvent event, FlowContext context, Map<String, Object> attributes, String flowId) {
    StringBuilder sb = new StringBuilder();
    sb.append("flow_id:").append(sanitizeLtsv(flowId));
    sb.append("\tevent:").append(sanitizeLtsv(event.getEventName()));
    sb.append("\tclient_ip:").append(sanitizeLtsv(getClientIp(context)));

    // Add all event-specific attributes
    if (attributes != null) {
      for (Map.Entry<String, Object> entry : attributes.entrySet()) {
        sb.append("\t")
            .append(sanitizeLtsvLabel(entry.getKey()))
            .append(":")
            .append(sanitizeLtsv(entry.getValue() != null ? entry.getValue().toString() : null));
      }
    }
    return sb.toString();
  }

  private String sanitizeLtsv(String value) {
    if (value == null) {
      return "-";
    }
    return value.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
  }

  private String sanitizeLtsvLabel(String label) {
    if (label == null || label.isEmpty()) {
      return "-";
    }
    return label.replaceAll("[^0-9A-Za-z_.-]", "_");
  }
}
