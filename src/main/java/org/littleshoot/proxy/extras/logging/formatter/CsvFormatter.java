package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.*;
import org.littleshoot.proxy.extras.logging.ActivityLogger.TimedRequest;

/**
 * Formatter for Comma-Separated Values (CSV) format.
 *
 * <p>CSV format uses quoted values separated by commas. All values are CSV-escaped for safety.
 *
 * <p>Example: "abc123","127.0.0.1","GET","/path","200","1234"
 */
public class CsvFormatter extends AbstractLogEntryFormatter {

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

    // Comma-Separated Values
    sb.append("\"").append(escapeCsv(flowId)).append("\"");
    for (LogField field : fieldConfig.getFields()) {
      // Handle pattern-based fields that expand to multiple entries - flatten into single cell
      if (field instanceof PrefixRequestHeaderField) {
        PrefixRequestHeaderField prefixField = (PrefixRequestHeaderField) field;
        sb.append(",\"")
            .append(
                escapeCsv(flattenHeaders(prefixField.extractMatchingHeaders(request.headers()))))
            .append("\"");
      } else if (field instanceof PrefixResponseHeaderField) {
        PrefixResponseHeaderField prefixField = (PrefixResponseHeaderField) field;
        sb.append(",\"")
            .append(
                escapeCsv(flattenHeaders(prefixField.extractMatchingHeaders(response.headers()))))
            .append("\"");
      } else if (field instanceof RegexRequestHeaderField) {
        RegexRequestHeaderField regexField = (RegexRequestHeaderField) field;
        sb.append(",\"")
            .append(escapeCsv(flattenHeaders(regexField.extractMatchingHeaders(request.headers()))))
            .append("\"");
      } else if (field instanceof RegexResponseHeaderField) {
        RegexResponseHeaderField regexField = (RegexResponseHeaderField) field;
        sb.append(",\"")
            .append(
                escapeCsv(flattenHeaders(regexField.extractMatchingHeaders(response.headers()))))
            .append("\"");
      } else if (field instanceof ExcludeRequestHeaderField) {
        ExcludeRequestHeaderField excludeField = (ExcludeRequestHeaderField) field;
        sb.append(",\"")
            .append(
                escapeCsv(flattenHeaders(excludeField.extractMatchingHeaders(request.headers()))))
            .append("\"");
      } else if (field instanceof ExcludeResponseHeaderField) {
        ExcludeResponseHeaderField excludeField = (ExcludeResponseHeaderField) field;
        sb.append(",\"")
            .append(
                escapeCsv(flattenHeaders(excludeField.extractMatchingHeaders(response.headers()))))
            .append("\"");
      } else {
        String value = field.extractValue(context, request, response, requestTimingData);
        // For CSV, use empty string for null values (maintains column alignment)
        String csvValue = value != null ? value : "";
        sb.append(",");

        sb.append("\"").append(escapeCsv(csvValue)).append("\"");
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
    sb.append("\"").append(escapeCsv(flowId)).append("\"");
    sb.append(",\"").append(escapeCsv(event.getEventName())).append("\"");
    sb.append(",\"").append(escapeCsv(getClientIp(context))).append("\"");

    // Add all event-specific attributes
    if (attributes != null) {
      sb.append(",\"")
          .append(
              escapeCsv(
                  attributes.entrySet().stream()
                      .sorted(Map.Entry.comparingByKey())
                      .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                      .collect(Collectors.joining(";"))))
          .append("\"");
    }

    return sb.toString();
  }

  private String flattenHeaders(Map<String, String> headers) {
    return headers.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(";"));
  }

  private String escapeCsv(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\"\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
