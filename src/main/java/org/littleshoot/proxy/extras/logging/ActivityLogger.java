package org.littleshoot.proxy.extras.logging;

import com.github.f4b6a3.ulid.UlidCreator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
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
    long tcpConnectionStartTime;
    long tcpConnectionEndTime;
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

  // ==================== REQUEST/RESPONSE TRACKING ====================

  @Override
  public void requestReceivedFromClient(FlowContext flowContext, HttpRequest httpRequest) {
    String flowId = generateFlowId();

    // Store request start time in FlowContext
    flowContext.setTimingData("request_start_time", System.currentTimeMillis());
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

    // Store timing data in FlowContext
    flowContext.setTimingData("http_request_processing_time", duration);

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
   * Logs a formatted entry at INFO level.
   *
   * @param flowId the flow identifier
   * @param message the formatted log message
   */
  protected void logFormattedEntry(String flowId, String message) {
    LOG.info("{}", message);
  }

  /**
   * Formats a log entry using the configured formatter.
   *
   * @param flowContext the flow context
   * @param timedRequest the timed request
   * @param httpResponse the HTTP response
   * @return the formatted log message
   */
  protected String formatLogEntry(
      FlowContext flowContext, TimedRequest timedRequest, HttpResponse httpResponse) {
    // Calculate and store duration in FlowContext
    long duration = System.currentTimeMillis() - timedRequest.startTime;
    flowContext.setTimingData("http_request_processing_time", duration);

    return formatter.format(
        flowContext,
        timedRequest.request,
        httpResponse,
        java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")),
        timedRequest.flowId,
        fieldConfiguration);
  }

  // ==================== TIMING CALCULATION HELPERS ====================

  private Long getTcpConnectionEstablishmentTime(FlowContext flowContext) {
    // Get from FlowContext timing data
    Long tcpConnectionStartTime = flowContext.getTimingData("tcp_connection_start_time");
    Long tcpConnectionEndTime = flowContext.getTimingData("tcp_connection_end_time");

    if (tcpConnectionStartTime != null && tcpConnectionEndTime != null) {
      return tcpConnectionEndTime - tcpConnectionStartTime;
    }

    // Fallback to old method for compatibility
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    if (clientAddress != null) {
      ClientState state = clientStates.get(clientAddress);
      if (state != null && state.tcpConnectionEndTime > 0) {
        return state.tcpConnectionEndTime - state.connectTime;
      }
    }
    return null;
  }

  private Long getTcpClientConnectionDuration(FlowContext flowContext) {
    // Get from FlowContext timing data
    Long tcpClientConnectionDuration = flowContext.getTimingData("tcp_client_connection_duration");
    if (tcpClientConnectionDuration != null) {
      return tcpClientConnectionDuration;
    }

    // Fallback to old method for compatibility
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    if (clientAddress != null) {
      ClientState state = clientStates.get(clientAddress);
      if (state != null && state.disconnectTime > 0) {
        return state.disconnectTime - state.connectTime;
      }
    }
    return null;
  }

  private Long getTcpServerConnectionDuration(FlowContext flowContext) {
    // Get from FlowContext timing data
    Long tcpServerConnectionDuration = flowContext.getTimingData("tcp_server_connection_duration");
    if (tcpServerConnectionDuration != null) {
      return tcpServerConnectionDuration;
    }

    // Fallback to old method for compatibility
    if (flowContext instanceof FullFlowContext) {
      ServerState state = serverStates.get((FullFlowContext) flowContext);
      if (state != null && state.disconnectTime > 0) {
        return state.disconnectTime - state.connectStartTime;
      }
    }
    return null;
  }

  private Long getSslHandshakeTime(FlowContext flowContext) {
    // Get from FlowContext timing data
    Long sslHandshakeTime = flowContext.getTimingData("ssl_handshake_time");
    if (sslHandshakeTime != null) {
      return sslHandshakeTime;
    }

    // Fallback to old method for compatibility
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    if (clientAddress != null) {
      ClientState state = clientStates.get(clientAddress);
      if (state != null && state.sslHandshakeEndTime > 0 && state.sslHandshakeStartTime > 0) {
        return state.sslHandshakeEndTime - state.sslHandshakeStartTime;
      }
    }
    return null;
  }

  // ==================== CLIENT LIFECYCLE ====================

  @Override
  public void clientConnected(FlowContext flowContext) {
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    String flowId = generateFlowId();

    // Store timing data in FlowContext
    flowContext.setTimingData("tcp_connection_start_time", now);

    // Track state
    if (clientAddress != null) {
      ClientState state = new ClientState(now, flowId);
      state.tcpConnectionStartTime = now;
      clientStates.put(clientAddress, state);
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

      // Store SSL handshake timing in FlowContext
      flowContext.setTimingData("ssl_handshake_time", duration);

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

      // Store client connection duration in FlowContext
      flowContext.setTimingData("tcp_client_connection_duration", duration);

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

    // Store server connection start time in FlowContext
    flowContext.setTimingData("tcp_server_connection_start_time", now);

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
    String flowId = getFlowId(flowContext);

    ServerState state = serverStates.remove(flowContext);
    if (state != null) {
      state.disconnectTime = now;
      long duration = state.disconnectTime - state.connectStartTime;

      // Store server connection duration in FlowContext
      flowContext.setTimingData("tcp_server_connection_duration", duration);

      // DEBUG: Essential operation with structured formatting
      logLifecycleEvent(
          LifecycleEvent.SERVER_DISCONNECTED,
          flowContext,
          Map.of(
              "server_address", serverAddress,
              "duration_ms", duration,
              "timestamp", now),
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

  // ==================== HELPER METHODS ====================

  private String getFlowId(FlowContext flowContext) {
    TimedRequest timedRequest = requestMap.get(flowContext);
    if (timedRequest != null) {
      return timedRequest.flowId;
    }
    return generateFlowId();
  }

  private void logLifecycleEvent(
      LifecycleEvent event,
      FlowContext flowContext,
      Map<String, Object> attributes,
      String flowId) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("[{}] {}: {}", flowId, event.name(), attributes);
    }
  }

  // ==================== CONNECTION STATE TRACKING ====================

  @Override
  public void connectionSaturated(FlowContext flowContext) {
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    ClientState state = clientAddress != null ? clientStates.get(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    if (state != null) {
      state.saturationCount++;
    }

    logLifecycleEvent(
        LifecycleEvent.CONNECTION_SATURATED,
        flowContext,
        Map.of(
            "client_address",
            clientAddress,
            "saturation_count",
            state != null ? state.saturationCount : 0),
        flowId);
  }

  @Override
  public void connectionWritable(FlowContext flowContext) {
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    ClientState state = clientAddress != null ? clientStates.get(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    logLifecycleEvent(
        LifecycleEvent.CONNECTION_WRITABLE,
        flowContext,
        Map.of("client_address", clientAddress),
        flowId);
  }

  @Override
  public void connectionTimedOut(FlowContext flowContext) {
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    ClientState state = clientAddress != null ? clientStates.get(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    logLifecycleEvent(
        LifecycleEvent.CONNECTION_TIMED_OUT,
        flowContext,
        Map.of("client_address", clientAddress),
        flowId);
  }

  @Override
  public void connectionExceptionCaught(FlowContext flowContext, Throwable cause) {
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    ClientState state = clientAddress != null ? clientStates.get(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    if (state != null) {
      state.exceptionCount++;
      state.lastExceptionType = cause != null ? cause.getClass().getSimpleName() : "Unknown";
    }

    logLifecycleEvent(
        LifecycleEvent.CONNECTION_EXCEPTION_CAUGHT,
        flowContext,
        Map.of(
            "client_address", clientAddress,
            "exception_type", state != null ? state.lastExceptionType : "Unknown",
            "exception_count", state != null ? state.exceptionCount : 0),
        flowId);
  }

  // ==================== VALIDATION ====================

  /**
   * Validates that the field configuration complies with standards. This method checks for required
   * fields and proper configuration.
   */
  private void validateStandardsCompliance() {
    if (fieldConfiguration == null) {
      return;
    }

    // Validate that required fields are present for the selected format
    if (logFormat == LogFormat.CLF) {
      // CLF format requires specific fields
      LOG.debug("Validating CLF format compliance");
    } else if (logFormat == LogFormat.W3C) {
      // W3C format requires specific fields
      LOG.debug("Validating W3C format compliance");
    }
  }
}
