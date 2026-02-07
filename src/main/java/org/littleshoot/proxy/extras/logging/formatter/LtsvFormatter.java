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
      long durationMs,
      ZonedDateTime now,
      String flowId,
      LogFieldConfiguration fieldConfig) {

    StringBuilder sb = new StringBuilder();

    // Labeled Tab-Separated Values
    sb.append("flow_id:").append(flowId);
    boolean first = false;
    for (LogField field : fieldConfig.getFields()) {
      // Handle prefix-based fields that expand to multiple entries
      if (field instanceof PrefixRequestHeaderField) {
        PrefixRequestHeaderField prefixField = (PrefixRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            prefixField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof PrefixResponseHeaderField) {
        PrefixResponseHeaderField prefixField = (PrefixResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            prefixField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof RegexRequestHeaderField) {
        RegexRequestHeaderField regexField = (RegexRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            regexField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof RegexResponseHeaderField) {
        RegexResponseHeaderField regexField = (RegexResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            regexField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof ExcludeRequestHeaderField) {
        ExcludeRequestHeaderField excludeField = (ExcludeRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            excludeField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append("\t");
          }
          first = false;
          sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
      } else if (field instanceof ExcludeResponseHeaderField) {
        ExcludeResponseHeaderField excludeField = (ExcludeResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            excludeField.extractMatchingHeaders(response.headers()).entrySet()) {
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

        String value = field.extractValue(context, request, response, durationMs);
        sb.append(field.getName()).append(":").append(value);
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
    sb.append("event:").append(event.getEventName());
    sb.append("\tflow_id:").append(flowId);
    sb.append("\tclient_ip:").append(getClientIp(context));

    // Add all event-specific attributes
    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
      sb.append("\t").append(entry.getKey()).append(":").append(entry.getValue());
    }

    return sb.toString();
  }
}
