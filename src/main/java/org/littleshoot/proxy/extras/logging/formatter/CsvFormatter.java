package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.Map;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.*;

/**
 * Formatter for Comma-Separated Values (CSV) format.
 *
 * <p>CSV format uses quoted values separated by commas. All values are JSON-escaped for safety.
 *
 * <p>Example: "abc123","127.0.0.1","GET","/path","200","1234"
 */
public class CsvFormatter extends AbstractLogEntryFormatter {

  @Override
  public String format(
      FlowContext context,
      HttpRequest request,
      HttpResponse response,
      ZonedDateTime now,
      String flowId,
      LogFieldConfiguration fieldConfig) {

    StringBuilder sb = new StringBuilder();

    // Comma-Separated Values
    sb.append("\"").append(escapeJson(flowId)).append("\"");
    boolean first = false;
    for (LogField field : fieldConfig.getFields()) {
      // Handle prefix-based fields that expand to multiple entries
      if (field instanceof PrefixRequestHeaderField) {
        PrefixRequestHeaderField prefixField = (PrefixRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            prefixField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof PrefixResponseHeaderField) {
        PrefixResponseHeaderField prefixField = (PrefixResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            prefixField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof RegexRequestHeaderField) {
        RegexRequestHeaderField regexField = (RegexRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            regexField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof RegexResponseHeaderField) {
        RegexResponseHeaderField regexField = (RegexResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            regexField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof ExcludeRequestHeaderField) {
        ExcludeRequestHeaderField excludeField = (ExcludeRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            excludeField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else if (field instanceof ExcludeResponseHeaderField) {
        ExcludeResponseHeaderField excludeField = (ExcludeResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            excludeField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
        }
      } else {
        String value = field.extractValue(context, request, response);
        // For CSV, use empty string for null values (maintains column alignment)
        String csvValue = value != null ? value : "";
        if (!first) {
          sb.append(",");
        }
        first = false;

        sb.append("\"").append(escapeJson(csvValue)).append("\"");
      }
    }

    return sb.toString();
  }

  @Override
  public LogFormat getSupportedFormat() {
    return LogFormat.CSV;
  }

  @Override
  public String formatLifecycleEvent(
      LifecycleEvent event, FlowContext context, Map<String, Object> attributes, String flowId) {
    StringBuilder sb = new StringBuilder();
    sb.append("\"").append(escapeJson(flowId)).append("\"");
    sb.append(",\"").append(event.getEventName()).append("\"");
    sb.append(",\"").append(escapeJson(getClientIp(context))).append("\"");

    // Add all event-specific attributes
    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
      sb.append(",\"").append(escapeJson(String.valueOf(entry.getValue()))).append("\"");
    }

    return sb.toString();
  }
}
