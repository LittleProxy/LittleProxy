package org.littleshoot.proxy.extras.logging;

import com.github.f4b6a3.ulid.UlidCreator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLSession;
import org.littleshoot.proxy.ActivityTrackerAdapter;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.extras.logging.formatter.LifecycleEvent;
import org.littleshoot.proxy.extras.logging.formatter.LogEntryFormatter;
import org.littleshoot.proxy.extras.logging.formatter.LogEntryFormatterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link org.littleshoot.proxy.ActivityTracker} that logs HTTP activity with a three-tier
 * logging strategy:
 *
 * <ul>
 *   <li><strong>TRACE</strong>: Detailed diagnostics - method entry, state transitions, thread info
 *   <li><strong>DEBUG</strong>: Essential operations - connections, disconnections, errors
 *   <li><strong>INFO</strong>: Complete interaction summary - aggregated metrics per
 *       request/response
 * </ul>
 */
public class ActivityLogger extends ActivityTrackerAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(ActivityLogger.class);
  public static final String UTC = "UTC";

  // Flow ID generator using ULID for globally unique, sortable identifiers

  private final LogFormat logFormat;
  private final LogFieldConfiguration fieldConfiguration;
  private final LogEntryFormatter formatter;

  /** Tracks request timing information. */
  private static class TimedRequest {
    final HttpRequest request;
    final long startTime;
    final String flowId;

    TimedRequest(HttpRequest request, long startTime, String flowId) {
      this.request = request;
      this.startTime = startTime;
      this.flowId = flowId;
    }
  }

  /** Tracks client connection state and metrics. */
  private static class ClientState {
    final long connectTime;
    long sslHandshakeStartTime;
    long sslHandshakeEndTime;
    long disconnectTime;
    SSLSession sslSession;
    int saturationCount;
    int exceptionCount;
    String lastExceptionType;
    final String flowId;

    ClientState(long connectTime, String flowId) {
      this.connectTime = connectTime;
      this.flowId = flowId;
    }
  }

  /** Tracks server connection state and metrics. */
  private static class ServerState {
    final long connectStartTime;
    long connectEndTime;
    long disconnectTime;
    InetSocketAddress remoteAddress;
    int saturationCount;

    ServerState(long connectStartTime, InetSocketAddress remoteAddress) {
      this.connectStartTime = connectStartTime;
      this.remoteAddress = remoteAddress;
    }
  }

  private final Map<FlowContext, TimedRequest> requestMap = new ConcurrentHashMap<>();
  private final Map<InetSocketAddress, ClientState> clientStates = new ConcurrentHashMap<>();
  private final Map<FullFlowContext, ServerState> serverStates = new ConcurrentHashMap<>();

  public ActivityLogger(LogFormat logFormat, LogFieldConfiguration fieldConfiguration) {
    this.logFormat = logFormat;
    this.fieldConfiguration =
        fieldConfiguration != null ? fieldConfiguration : LogFieldConfiguration.defaultConfig();
    this.formatter = LogEntryFormatterFactory.getFormatter(logFormat);
    validateStandardsCompliance();
  }

  /**
   * Generates a unique flow ID for tracing requests across the proxy.
   *
   * @return unique flow identifier
   */
  private String generateFlowId() {
    return UlidCreator.getUlid().toString();
  }

  /**
   * Validates that the current configuration complies with logging standards. Throws
   * IllegalArgumentException if configuration violates standards.
   */
  private void validateStandardsCompliance() {
    if (fieldConfiguration.isStrictStandardsCompliance()) {
      switch (logFormat) {
        case CLF:
          // CLF format is very strict - only allows standard fields
          for (LogField field : fieldConfiguration.getFields()) {
            if (!(field instanceof StandardField)
                || field == StandardField.REFERER
                || field == StandardField.USER_AGENT) {
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

  // ==================== REQUEST/RESPONSE TRACKING ====================

  @Override
  public void requestReceivedFromClient(FlowContext flowContext, HttpRequest httpRequest) {
    String flowId = generateFlowId();

    requestMap.put(flowContext, new TimedRequest(httpRequest, System.currentTimeMillis(), flowId));

    // DEBUG: Structured formatting for request received
    logLifecycleEvent(
        LifecycleEvent.REQUEST_RECEIVED,
        flowContext,
        Map.of(
            "method", httpRequest.method(),
            "uri", httpRequest.uri(),
            "client_address", flowContext.getClientAddress()),
        flowId);
  }

  @Override
  public void responseSentToClient(FlowContext flowContext, HttpResponse httpResponse) {
    TimedRequest timedRequest = requestMap.remove(flowContext);
    if (timedRequest == null) {
      return;
    }

    String flowId = timedRequest.flowId;
    long duration = System.currentTimeMillis() - timedRequest.startTime;

    // DEBUG: Structured formatting for response sent
    logLifecycleEvent(
        LifecycleEvent.RESPONSE_SENT,
        flowContext,
        Map.of("status", httpResponse.status().code(), "duration_ms", duration),
        flowId);

    // INFO: Use configured format (KEYVALUE, JSON, etc.)
    if (shouldLogInfoEntry()) {
      String logMessage = formatLogEntry(flowContext, timedRequest, httpResponse);
      if (logMessage != null) {
        logFormattedEntry(flowId, logMessage);
      }
    }

    // DEBUG: Also log formatted entry at debug level if different from INFO
    if (LOG.isDebugEnabled() && logFormat != LogFormat.KEYVALUE) {
      String logMessage = formatLogEntry(flowContext, timedRequest, httpResponse);
      if (logMessage != null) {
        logFormattedEntry(flowId, logMessage);
      }
    }
  }

  /**
   * Determines if INFO level log entries should be logged. This method can be overridden in tests
   * to force logging regardless of the logger's info level.
   *
   * @return true if INFO entries should be logged
   */
  protected boolean shouldLogInfoEntry() {
    return LOG.isInfoEnabled();
  }

  /**
   * Logs a formatted entry with flowId prefix at INFO level.
   *
   * @param flowId the flow identifier
   * @param message the formatted log message
   */
  protected void logFormattedEntry(String flowId, String message) {
    LOG.info("[{}] {}", flowId, message);
  }


  // ==================== CLIENT LIFECYCLE ====================

  @Override
  public void clientConnected(FlowContext flowContext) {
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    String flowId = generateFlowId();

    // Track state
    if (clientAddress != null) {
      clientStates.put(clientAddress, new ClientState(now, flowId));
    }

    // DEBUG: Essential operation with structured formatting
    logLifecycleEvent(
        LifecycleEvent.CLIENT_CONNECTED,
        flowContext,
        Map.of("client_address", clientAddress, "timestamp", now),
        flowId);
  }

  @Override
  public void clientSSLHandshakeSucceeded(FlowContext flowContext, SSLSession sslSession) {
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    ClientState state = clientAddress != null ? clientStates.get(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    if (state != null) {
      state.sslHandshakeEndTime = now;
      state.sslSession = sslSession;
      long duration = state.sslHandshakeEndTime - state.sslHandshakeStartTime;

      // DEBUG: Essential operation with structured formatting
      logLifecycleEvent(
          LifecycleEvent.CLIENT_SSL_HANDSHAKE_SUCCEEDED,
          flowContext,
          Map.of(
              "client_address",
              clientAddress,
              "protocol",
              sslSession.getProtocol(),
              "cipher_suite",
              sslSession.getCipherSuite(),
              "duration_ms",
              duration),
          flowId);
    }
  }

  @Override
  public void clientDisconnected(FlowContext flowContext, SSLSession sslSession) {
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    ClientState state = clientAddress != null ? clientStates.remove(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    if (state != null) {
      state.disconnectTime = now;
      long duration = state.disconnectTime - state.connectTime;

      // DEBUG: Essential operation with structured formatting
      logLifecycleEvent(
          LifecycleEvent.CLIENT_DISCONNECTED,
          flowContext,
          Map.of("client_address", clientAddress, "duration_ms", duration, "timestamp", now),
          flowId);
    } else {
      // DEBUG: Still log even if state not found
      logLifecycleEvent(
          LifecycleEvent.CLIENT_DISCONNECTED,
          flowContext,
          Map.of("client_address", clientAddress, "state_found", false, "timestamp", now),
          flowId);
    }
  }

  // ==================== SERVER LIFECYCLE ====================

  @Override
  public void serverConnected(FullFlowContext flowContext, InetSocketAddress serverAddress) {
    long now = System.currentTimeMillis();
    String flowId = getFlowId(flowContext);

    // Track state
    serverStates.put(flowContext, new ServerState(now, serverAddress));

    // DEBUG: Essential operation with structured formatting
    logLifecycleEvent(
        LifecycleEvent.SERVER_CONNECTED,
        flowContext,
        Map.of("server_address", serverAddress, "timestamp", now),
        flowId);
  }

  @Override
  public void serverDisconnected(FullFlowContext flowContext, InetSocketAddress serverAddress) {
    long now = System.currentTimeMillis();
    ServerState state = serverStates.remove(flowContext);
    String flowId = getFlowId(flowContext);

    if (state != null) {
      state.disconnectTime = now;
      long duration = state.connectEndTime > 0 ? state.disconnectTime - state.connectEndTime : 0;
      long timeToConnect = state.connectEndTime - state.connectStartTime;

      // DEBUG: Essential operation with structured formatting
      logLifecycleEvent(
          LifecycleEvent.SERVER_DISCONNECTED,
          flowContext,
          Map.of(
              "server_address",
              serverAddress,
              "connection_duration_ms",
              duration,
              "time_to_connect_ms",
              timeToConnect,
              "timestamp",
              now),
          flowId);
    } else {
      // DEBUG: Still log even if state not found
      logLifecycleEvent(
          LifecycleEvent.SERVER_DISCONNECTED,
          flowContext,
          Map.of("server_address", serverAddress, "state_found", false, "timestamp", now),
          flowId);
    }
  }

  // ==================== CONNECTION STATE ====================

  @Override
  public void connectionSaturated(FlowContext flowContext) {
    String flowId = getFlowId(flowContext);
    String side = isClientContext(flowContext) ? "client" : "server";

    // Update state counts
    if (flowContext instanceof FullFlowContext) {
      ServerState state = serverStates.get((FullFlowContext) flowContext);
      if (state != null) {
        state.saturationCount++;
      }
    } else {
      InetSocketAddress clientAddress = flowContext.getClientAddress();
      if (clientAddress != null) {
        ClientState state = clientStates.get(clientAddress);
        if (state != null) {
          state.saturationCount++;
        }
      }
    }

    // DEBUG: Structured formatting
    logLifecycleEvent(
        LifecycleEvent.CONNECTION_SATURATED, flowContext, Map.of("side", side), flowId);
  }

  @Override
  public void connectionWritable(FlowContext flowContext) {
    String flowId = getFlowId(flowContext);
    String side = isClientContext(flowContext) ? "client" : "server";

    // DEBUG: Structured formatting
    logLifecycleEvent(
        LifecycleEvent.CONNECTION_WRITABLE, flowContext, Map.of("side", side), flowId);
  }

  @Override
  public void connectionTimedOut(FlowContext flowContext) {
    String side = isClientContext(flowContext) ? "client" : "server";
    String flowId = getFlowId(flowContext);

    // DEBUG: Structured formatting
    logLifecycleEvent(
        LifecycleEvent.CONNECTION_TIMED_OUT, flowContext, Map.of("side", side), flowId);
  }

  @Override
  public void connectionExceptionCaught(FlowContext flowContext, Throwable cause) {
    String side = isClientContext(flowContext) ? "client" : "server";
    String flowId = getFlowId(flowContext);

    // DEBUG: Structured formatting with exception details
    logLifecycleEvent(
        LifecycleEvent.CONNECTION_EXCEPTION_CAUGHT,
        flowContext,
        Map.of(
            "side",
            side,
            "exception_type",
            cause.getClass().getSimpleName(),
            "exception_message",
            cause.getMessage()),
        flowId);

    // Track exception in state
    if (flowContext instanceof FullFlowContext) {
      ServerState state = serverStates.get((FullFlowContext) flowContext);
      if (state != null) {
        // Server exceptions tracked per flow
      }
    } else {
      InetSocketAddress clientAddress = flowContext.getClientAddress();
      if (clientAddress != null) {
        ClientState state = clientStates.get(clientAddress);
        if (state != null) {
          state.exceptionCount++;
          state.lastExceptionType = cause.getClass().getSimpleName();
        }
      }
    }
  }

  // ==================== HELPER METHODS ====================

  private String getFlowId(FlowContext flowContext) {
    if (flowContext == null) {
      return "unknown";
    }
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    if (clientAddress == null) {
      return "unknown";
    }
    ClientState state = clientStates.get(clientAddress);
    return state != null ? state.flowId : "unknown";
  }

  /**
   * Determines if the given flow context represents the client-side connection.
   *
   * @param flowContext the flow context to check
   * @return true if this is a client-side context, false if server-side
   */
  private boolean isClientContext(FlowContext flowContext) {
    // Client context is a basic FlowContext without server information
    return !(flowContext instanceof FullFlowContext);
  }

  /**
   * Formats a log entry using the configured formatter. This method delegates to the appropriate
   * LogEntryFormatter implementation based on the configured log format.
   */
  private String formatLogEntry(
      FlowContext flowContext, TimedRequest timedRequest, HttpResponse response) {
    long duration = System.currentTimeMillis() - timedRequest.startTime;
    ZonedDateTime now = ZonedDateTime.now(ZoneId.of(UTC));

    return formatter.format(
        flowContext,
        timedRequest.request,
        response,
        duration,
        now,
        timedRequest.flowId,
        fieldConfiguration);
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
   * Logs a lifecycle event at DEBUG level using the configured formatter. Falls back to simple
   * formatting if the configured format doesn't support lifecycle events.
   *
   * @param event the lifecycle event type
   * @param context the flow context
   * @param attributes map of event-specific attributes
   * @param flowId the flow identifier
   */
  private void logLifecycleEvent(
      LifecycleEvent event, FlowContext context, Map<String, Object> attributes, String flowId) {
    if (!LOG.isDebugEnabled()) {
      return;
    }

    String formatted = formatter.formatLifecycleEvent(event, context, attributes, flowId);
    if (formatted != null) {
      LOG.debug(formatted);
    } else {
      // Fallback for formats that don't support lifecycle events (CLF, ELF, etc.)
      StringBuilder sb = new StringBuilder();
      sb.append("[").append(flowId).append("] ").append(event.getEventName());
      for (Map.Entry<String, Object> entry : attributes.entrySet()) {
        sb.append(" ").append(entry.getKey()).append("=").append(entry.getValue());
      }
      LOG.debug(sb.toString());
    }
  }
}
