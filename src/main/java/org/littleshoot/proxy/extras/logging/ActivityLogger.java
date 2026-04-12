package org.littleshoot.proxy.extras.logging;

import com.google.common.base.Preconditions;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
  public static final ZoneId UTC_ZONE_ID = ZoneId.of(UTC);

  private final LogFormat logFormat;
  private final LogFieldConfiguration fieldConfiguration;
  private final LogEntryFormatter formatter;
  private final TimingMode timingMode;

  /** Tracks request timing information. */
  private static class TimedRequest {
    final HttpRequest request;
    final long startTime;
    final String flowId;
    private final Map<String, Long> data = new ConcurrentHashMap<>();

    TimedRequest(HttpRequest request, long startTime, String flowId) {
      this.request = request;
      this.startTime = startTime;
      this.flowId = flowId;
    }

    /**
     * Stores timing data for this flow.
     *
     * @param key the timing metric key
     * @param value the timing value in milliseconds
     */
    public void setTimingData(String key, Long value) {
      Objects.requireNonNull(key, "timing key must not be null");
      Objects.requireNonNull(value, "timing value must not be null");
      data.put(key, value);
    }

    /**
     * Retrieves timing data for this flow.
     *
     * @param key the timing metric key
     * @return the timing value in milliseconds, or null if not available
     */
    public Long getTimingData(String key) {
      return data.get(key);
    }

    /**
     * Gets all timing data for this flow.
     *
     * @return map of all timing data
     */
    public Map<String, Long> getTimings() {
      return Map.copyOf(data);
    }
  }

  /** Tracks flow ID for client connections. */
  private static class ClientState {
    final String flowId;

    ClientState(String flowId) {
      this.flowId = flowId;
    }
  }

  /** Tracks flow ID for server connections. */
  private static class ServerState {
    final String flowId;
    final InetSocketAddress remoteAddress;

    ServerState(String flowId, InetSocketAddress remoteAddress) {
      this.flowId = flowId;
      this.remoteAddress = remoteAddress;
    }
  }

  private final Map<String, TimedRequest> requestMap = new ConcurrentHashMap<>();
  private final Map<InetSocketAddress, ClientState> clientStates = new ConcurrentHashMap<>();
  private final Map<FullFlowContext, ServerState> serverStates = new ConcurrentHashMap<>();

  public ActivityLogger(LogFormat logFormat, LogFieldConfiguration fieldConfiguration) {
    this(logFormat, fieldConfiguration, TimingMode.MINIMAL);
  }

  public ActivityLogger(
      LogFormat logFormat, LogFieldConfiguration fieldConfiguration, TimingMode timingMode) {
    this.logFormat = logFormat;
    this.fieldConfiguration =
        fieldConfiguration != null ? fieldConfiguration : LogFieldConfiguration.defaultConfig();
    this.formatter = LogEntryFormatterFactory.getFormatter(logFormat);
    this.timingMode = timingMode != null ? timingMode : TimingMode.MINIMAL;
    validateStandardsCompliance();
  }

  // ==================== REQUEST/RESPONSE TRACKING ====================

  @Override
  public void requestReceivedFromClient(
      FlowContext flowContext, HttpRequest httpRequest, String requestId) {

    long now = System.currentTimeMillis();
    // Store request start time in FlowContext
    flowContext.setTimingData("request_start_time", now);
    TimedRequest timedRequest = new TimedRequest(httpRequest, now, flowContext.getFlowId());
    requestMap.put(requestId, timedRequest);

    // For non-SSL connections, set tcp_connection_establishment_time_ms if not already set
    // (For SSL connections, it's set in clientSSLHandshakeSucceeded)
    Long existingEstablishmentTime =
        flowContext.getTimingData("tcp_connection_establishment_time_ms");
    if (existingEstablishmentTime == null) {
      Long clientConnectionStartTime =
          flowContext.getTimingData("tcp_client_connection_start_time_ms");
      if (clientConnectionStartTime != null) {
        long tcpConnectionEstablishmentTimeMs = now - clientConnectionStartTime;
        flowContext.setTimingData(
            "tcp_connection_establishment_time_ms", tcpConnectionEstablishmentTimeMs);
      }
    }

    // DEBUG: Structured formatting for request received
    var requestAttributes = new java.util.HashMap<String, Object>();
    requestAttributes.put("method", httpRequest.method());
    requestAttributes.put("uri", httpRequest.uri());
    if (flowContext.getClientAddress() != null) {
      requestAttributes.put("client_address", flowContext.getClientAddress());
    }
    if (timingMode != TimingMode.OFF) {
      addClientTimingAttributes(requestAttributes, flowContext, now, false);
      Long lastRequest = flowContext.getTimingData("last_request_start_time_ms");
      if (lastRequest != null) {
        requestAttributes.put("time_since_previous_request_ms", now - lastRequest);
      }
      Long serverStart = flowContext.getTimingData("tcp_server_connection_start_time_ms");
      if (serverStart != null) {
        requestAttributes.put("time_since_server_connect_ms", now - serverStart);
      }
    }
    flowContext.setTimingData("last_request_start_time_ms", now);
    logLifecycleEvent(
        LifecycleEvent.REQUEST_RECEIVED,
        flowContext,
        java.util.Map.copyOf(requestAttributes),
        flowContext.getFlowId());
  }

  @Override
  public void responseReceivedFromServer(
      FullFlowContext fullFlowContext, HttpResponse httpResponse, String requestId) {
    long now = System.currentTimeMillis();
    String flowId = getFlowId(requestId);

    // Store the time when first response byte was received from server
    fullFlowContext.setTimingData("response_first_byte_time_ms", now);

    // Calculate response latency (TTFB - Time To First Byte)
    Long requestStartTime = fullFlowContext.getTimingData("request_start_time");
    if (requestStartTime != null) {
      long responseLatencyMs = now - requestStartTime;
      fullFlowContext.setTimingData("response_latency_ms", responseLatencyMs);
    }

    // DEBUG: Structured formatting for response received from server
    var responseAttributes = new java.util.HashMap<String, Object>();
    responseAttributes.put("status", httpResponse.status().code());
    responseAttributes.put("server_host", fullFlowContext.getServerHostAndPort());
    if (timingMode != TimingMode.OFF) {
      Long latency = fullFlowContext.getTimingData("response_latency_ms");
      if (latency != null) {
        responseAttributes.put("response_latency_ms", latency);
      }
    }
    logLifecycleEvent(
        LifecycleEvent.RESPONSE_RECEIVED_FROM_SERVER,
        fullFlowContext,
        java.util.Map.copyOf(responseAttributes),
        flowId);
  }

  @Override
  public void responseSentToClient(
      FlowContext flowContext, HttpResponse httpResponse, String requestId) {
    TimedRequest timedRequest = requestMap.remove(requestId);
    if (timedRequest == null) {
      return;
    }

    String flowId = timedRequest.flowId;
    long now = System.currentTimeMillis();
    long httpRequestProcessingTimeMs = now - timedRequest.startTime;

    // Store timing data in FlowContext
    timedRequest.setTimingData("http_request_processing_time_ms", httpRequestProcessingTimeMs);

    // Calculate response transfer time (from first byte to last byte)
    Long firstByteTime = timedRequest.getTimingData("response_first_byte_time_ms");
    if (firstByteTime != null) {
      long responseTransferTimeMs = now - firstByteTime;
      timedRequest.setTimingData("response_transfer_time_ms", responseTransferTimeMs);
    }

    // DEBUG: Structured formatting for response sent
    if (timingMode != TimingMode.OFF) {
      timedRequest.setTimingData("http_request_processing_time_ms", httpRequestProcessingTimeMs);
    }
    Map<String, Object> newMap = new HashMap<>(timedRequest.getTimings());
    newMap.put("status", "" + httpResponse.status().code());
    logLifecycleEvent(LifecycleEvent.RESPONSE_SENT, flowContext, newMap, flowId);

    // INFO: Use configured format (KEYVALUE, JSON, etc.)
    if (shouldLogInfoEntry()) {
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
    long httpRequestProcessingTimeMs = System.currentTimeMillis() - timedRequest.startTime;
    timedRequest.setTimingData("http_request_processing_time_ms", httpRequestProcessingTimeMs);

    return formatter.format(
        flowContext,
        timedRequest.request,
        httpResponse,
        java.time.ZonedDateTime.now(UTC_ZONE_ID),
        timedRequest.flowId,
        fieldConfiguration);
  }

  // ==================== CLIENT LIFECYCLE ====================

  @Override
  public void clientConnected(FlowContext flowContext) {
    Preconditions.checkNotNull(flowContext, "flowContext cannot be null");
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext.getClientAddress();

    // Store timing data in FlowContext
    flowContext.setTimingData("tcp_client_connection_start_time_ms", now);

    // Track state
    if (clientAddress != null) {
      ClientState state = new ClientState(flowContext.getFlowId());
      clientStates.put(clientAddress, state);
    }

    // DEBUG: Essential operation with structured formatting
    var attributes = new java.util.HashMap<String, Object>();
    attributes.put("timestamp", formatTimestamp(now));
    if (clientAddress != null) {
      attributes.put("client_address", clientAddress);
    }
    if (timingMode != TimingMode.OFF) {
      addClientTimingAttributes(attributes, flowContext, now, true);
    }

    logLifecycleEvent(
        LifecycleEvent.CLIENT_CONNECTED,
        flowContext,
        java.util.Map.copyOf(attributes),
        flowContext.getFlowId());
  }

  @Override
  public void clientSSLHandshakeStarted(FlowContext flowContext) {
    long now = System.currentTimeMillis();

    // Store SSL handshake start time in FlowContext
    flowContext.setTimingData("ssl_handshake_start_time_ms", now);
  }

  @Override
  public void clientSSLHandshakeSucceeded(FlowContext flowContext, SSLSession sslSession) {
    Preconditions.checkNotNull(flowContext, "flowContext cannot be null");
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    ClientState state = clientAddress != null ? clientStates.get(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    // Store SSL handshake end time in FlowContext
    flowContext.setTimingData("ssl_handshake_end_time_ms", now);

    // Calculate SSL handshake duration
    Long sslHandshakeStartTime = flowContext.getTimingData("ssl_handshake_start_time_ms");
    if (sslHandshakeStartTime != null) {
      long sslHandshakeTimeMs = now - sslHandshakeStartTime;
      flowContext.setTimingData("ssl_handshake_time_ms", sslHandshakeTimeMs);
    }

    // Calculate TCP connection establishment time (from client connect to SSL handshake complete)
    Long clientConnectionStartTime =
        flowContext.getTimingData("tcp_client_connection_start_time_ms");
    if (clientConnectionStartTime != null) {
      long tcpConnectionEstablishmentTimeMs = now - clientConnectionStartTime;
      flowContext.setTimingData(
          "tcp_connection_establishment_time_ms", tcpConnectionEstablishmentTimeMs);
    }

    // Build attributes map based on timing mode
    var attributesBuilder = new java.util.HashMap<String, Object>();
    if (clientAddress != null) {
      attributesBuilder.put("client_address", clientAddress);
    }
    attributesBuilder.put("protocol", sslSession.getProtocol());
    attributesBuilder.put("cipher_suite", sslSession.getCipherSuite());
    if (timingMode != TimingMode.OFF) {
      Long handshakeTime = flowContext.getTimingData("ssl_handshake_time_ms");
      if (handshakeTime != null) {
        attributesBuilder.put("ssl_handshake_time_ms", handshakeTime);
      }
    }
    attributesBuilder.putAll(flowContext.getTimings());

    // DEBUG: Essential operation with structured formatting
    logLifecycleEvent(
        LifecycleEvent.CLIENT_SSL_HANDSHAKE_SUCCEEDED,
        flowContext,
        java.util.Map.copyOf(attributesBuilder),
        flowId);
  }

  @Override
  public void clientDisconnected(FlowContext flowContext, SSLSession sslSession) {
    Preconditions.checkNotNull(flowContext, "flowContext cannot be null");
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    ClientState state = clientAddress != null ? clientStates.remove(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    // Store client connection end time in FlowContext
    flowContext.setTimingData("tcp_client_connection_end_time_ms", now);

    // Calculate duration if start time exists
    Long clientConnectionStartTime =
        flowContext.getTimingData("tcp_client_connection_start_time_ms");
    if (clientConnectionStartTime != null) {
      long tcpClientConnectionDurationMs = now - clientConnectionStartTime;
      flowContext.setTimingData("tcp_client_connection_duration_ms", tcpClientConnectionDurationMs);
    }

    // Build attributes map based on timing mode
    var attributesBuilder = new java.util.HashMap<String, Object>();
    if (clientAddress != null) {
      attributesBuilder.put("client_address", clientAddress);
    }
    attributesBuilder.put("timestamp", formatTimestamp(now));
    if (timingMode != TimingMode.OFF) {
      addClientTimingAttributes(attributesBuilder, flowContext, now, false);
      Long duration = flowContext.getTimingData("tcp_client_connection_duration_ms");
      if (duration != null) {
        attributesBuilder.put("tcp_client_connection_duration_ms", duration);
      }
      attributesBuilder.putAll(flowContext.getTimings());
    }

    // DEBUG: Essential operation with structured formatting
    logLifecycleEvent(
        LifecycleEvent.CLIENT_DISCONNECTED,
        flowContext,
        java.util.Map.copyOf(attributesBuilder),
        flowId);
  }

  // ==================== SERVER LIFECYCLE ====================

  @Override
  public void serverConnected(FullFlowContext flowContext, InetSocketAddress serverAddress) {
    Preconditions.checkNotNull(flowContext, "flowContext cannot be null");
    long now = System.currentTimeMillis();
    String flowId = getFlowId(flowContext);

    // Store server connection start time in FlowContext
    flowContext.setTimingData("tcp_server_connection_start_time_ms", now);

    // Track state
    serverStates.put(flowContext, new ServerState(flowId, serverAddress));

    // DEBUG: Essential operation with structured formatting
    logLifecycleEvent(
        LifecycleEvent.SERVER_CONNECTED,
        flowContext,
        buildServerEventAttributes(serverAddress, now, flowContext, false),
        flowId);
  }

  @Override
  public void serverDisconnected(FullFlowContext flowContext, InetSocketAddress serverAddress) {
    Preconditions.checkNotNull(flowContext, "flowContext cannot be null");
    long now = System.currentTimeMillis();
    String flowId = getFlowId(flowContext);

    // Store server connection end time in FlowContext
    flowContext.setTimingData("tcp_server_connection_end_time_ms", now);

    // Calculate duration if start time exists
    Long serverConnectionStartTime =
        flowContext.getTimingData("tcp_server_connection_start_time_ms");
    if (serverConnectionStartTime != null) {
      long tcpServerConnectionDurationMs = now - serverConnectionStartTime;
      flowContext.setTimingData("tcp_server_connection_duration_ms", tcpServerConnectionDurationMs);
    }

    // Remove server state tracking
    serverStates.remove(flowContext);

    // DEBUG: Essential operation with structured formatting
    logLifecycleEvent(
        LifecycleEvent.SERVER_DISCONNECTED,
        flowContext,
        buildServerEventAttributes(serverAddress, now, flowContext, true),
        flowId);
  }

  private Map<String, Object> buildServerEventAttributes(
      InetSocketAddress serverAddress, long now, FlowContext flowContext, boolean includeTimings) {
    var attributes = new java.util.HashMap<String, Object>();
    if (serverAddress != null) {
      attributes.put("server_address", serverAddress);
    }
    attributes.put("timestamp", formatTimestamp(now));
    if (timingMode != TimingMode.OFF) {
      if (includeTimings) {
        Long duration = flowContext.getTimingData("tcp_server_connection_duration_ms");
        if (duration != null) {
          flowContext.setTimingData("tcp_server_connection_duration_ms", duration);
        }
        Long serverStart = flowContext.getTimingData("tcp_server_connection_start_time_ms");
        if (serverStart != null) {
          flowContext.setTimingData("time_since_server_connect_ms", now - serverStart);
        }
      }
      Long dnsDuration = flowContext.getTimingData("dns_resolution_time_ms");
      if (dnsDuration != null) {
        flowContext.setTimingData("dns_resolution_time_ms", dnsDuration);
      }
      Long requestStart = flowContext.getTimingData("request_start_time");
      if (requestStart == null) {
        requestStart = flowContext.getTimingData("last_request_start_time_ms");
      }
      if (requestStart != null) {
        flowContext.setTimingData("time_since_client_request_ms", now - requestStart);
      }
      Long clientStart = flowContext.getTimingData("tcp_client_connection_start_time_ms");
      if (clientStart != null) {
        flowContext.setTimingData("server_connect_latency_ms", now - clientStart);
      }
    }
    attributes.putAll(flowContext.getTimings());
    return java.util.Map.copyOf(attributes);
  }

  private void addClientTimingAttributes(
      java.util.Map<String, Object> attributes,
      FlowContext flowContext,
      long now,
      boolean includeStartOnly) {
    Long clientStart = flowContext.getTimingData("tcp_client_connection_start_time_ms");
    if (clientStart != null) {
      flowContext.setTimingData("connection_age_ms", now - clientStart);
    }
    if (!includeStartOnly) {
      Long lastRequestTime = flowContext.getTimingData("last_request_start_time_ms");
      if (lastRequestTime != null) {
        flowContext.setTimingData("time_since_last_request_ms", now - lastRequestTime);
      }
      Long establishment = flowContext.getTimingData("tcp_connection_establishment_time_ms");
      if (establishment != null) {
        flowContext.setTimingData("tcp_connection_establishment_time_ms", establishment);
      }
    }
    attributes.putAll(flowContext.getTimings());
  }

  // ==================== HELPER METHODS ====================

  private String getFlowId(String requestId) {
    TimedRequest timedRequest = requestMap.get(requestId);
    if (timedRequest != null) {
      return timedRequest.flowId;
    }
    return null;
  }

  private String getFlowId(FlowContext flowContext) {
    return flowContext.getFlowId();
  }

  /**
   * Formats an epoch millisecond timestamp to ISO-8601 format with UTC timezone.
   *
   * @param epochMillis the epoch time in milliseconds
   * @return ISO-8601 formatted timestamp string
   */
  private String formatTimestamp(long epochMillis) {
    return java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(UTC_ZONE_ID)
        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private void logLifecycleEvent(
      LifecycleEvent event,
      FlowContext flowContext,
      Map<String, Object> attributes,
      String flowId) {
    if (LOG.isDebugEnabled()) {
      String formattedEvent =
          formatter.formatLifecycleEvent(event, flowContext, attributes, flowId);
      if (formattedEvent != null) {
        LOG.debug("{}", formattedEvent);
      } else {
        // Fallback to simple format if lifecycle events not supported by this formatter
        LOG.debug("[{}] {}: {}", flowId, event.name(), attributes);
      }
    }
  }

  // ==================== CONNECTION STATE TRACKING ====================

  @Override
  public void connectionSaturated(FlowContext flowContext) {
    Preconditions.checkNotNull(flowContext, "flowContext cannot be null");
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    ClientState state = clientAddress != null ? clientStates.get(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    // Increment saturation count in FlowContext
    Long currentCount = flowContext.getTimingData("client_saturation_count");
    long newCount = (currentCount != null ? currentCount : 0) + 1;
    flowContext.setTimingData("client_saturation_count", newCount);

    logLifecycleEvent(
        LifecycleEvent.CONNECTION_SATURATED,
        flowContext,
        Map.of("client_address", clientAddress, "saturation_count", newCount),
        flowId);
  }

  @Override
  public void connectionWritable(FlowContext flowContext) {
    Preconditions.checkNotNull(flowContext, "flowContext cannot be null");
    InetSocketAddress clientAddress = flowContext.getClientAddress();
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
    Preconditions.checkNotNull(flowContext, "flowContext cannot be null");
    InetSocketAddress clientAddress = flowContext.getClientAddress();
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
    Preconditions.checkNotNull(flowContext, "flowContext cannot be null");
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    ClientState state = clientAddress != null ? clientStates.get(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    // Increment exception count in FlowContext
    Long currentCount = flowContext.getTimingData("client_exception_count");
    long newCount = (currentCount != null ? currentCount : 0) + 1;
    flowContext.setTimingData("client_exception_count", newCount);

    // Get exception type for logging
    String exceptionType = cause != null ? cause.getClass().getSimpleName() : "Unknown";

    logLifecycleEvent(
        LifecycleEvent.CONNECTION_EXCEPTION_CAUGHT,
        flowContext,
        Map.of(
            "client_address", clientAddress,
            "exception_type", exceptionType,
            "exception_count", newCount),
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
      LOG.trace("Validating CLF format compliance");
    } else if (logFormat == LogFormat.W3C) {
      // W3C format requires specific fields
      LOG.trace("Validating W3C format compliance");
    }
  }
}
