package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.Map;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

/**
 * Strategy interface for formatting log entries in different formats. Implementations of this
 * interface handle the formatting of HTTP request/response pairs into specific log formats such as
 * CLF, JSON, CSV, etc.
 */
public interface LogEntryFormatter {

  /**
   * Formats a log entry from the given HTTP request/response context.
   *
   * @param context the flow context containing client and server connection information
   * @param request the HTTP request
   * @param response the HTTP response
   * @param durationMs the request processing duration in milliseconds
   * @param now the current timestamp with timezone
   * @param flowId the unique flow identifier for tracing
   * @param fieldConfig the field configuration determining which fields to include
   * @return the formatted log entry string
   */
  String format(
      FlowContext context,
      HttpRequest request,
      HttpResponse response,
      long durationMs,
      ZonedDateTime now,
      String flowId,
      LogFieldConfiguration fieldConfig);

  /**
   * Formats a lifecycle event for structured logging at DEBUG/TRACE levels.
   *
   * @param event the lifecycle event type
   * @param context the flow context containing client and server connection information
   * @param attributes map of event-specific attributes (e.g., client_address, duration_ms)
   * @param flowId the unique flow identifier for tracing
   * @return the formatted lifecycle event string, or null if this format doesn't support lifecycle
   *     events
   */
  String formatLifecycleEvent(
      LifecycleEvent event, FlowContext context, Map<String, Object> attributes, String flowId);

  /**
   * Returns the log format supported by this formatter.
   *
   * @return the LogFormat enum value
   */
  LogFormat getSupportedFormat();
}
