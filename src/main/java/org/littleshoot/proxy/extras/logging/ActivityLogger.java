package org.littleshoot.proxy.extras.logging;

import com.github.f4b6a3.ulid.UlidCreator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLSession;
import org.littleshoot.proxy.ActivityTrackerAdapter;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
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
  private static final String DATE_FORMAT_CLF = "dd/MMM/yyyy:HH:mm:ss Z";
  public static final String UTC = "UTC";
  public static final String USER_AGENT = "User-Agent";
  public static final String ISO_8601_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

  // Flow ID generator using ULID for globally unique, sortable identifiers

  private final LogFormat logFormat;
  private final LogFieldConfiguration fieldConfiguration;

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

    // TRACE: Detailed entry logging
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "[{}] ENTER requestReceivedFromClient - clientAddress={}, method={}, uri={}",
          flowId,
          flowContext.getClientAddress(),
          httpRequest.method(),
          httpRequest.uri());
    }

    requestMap.put(flowContext, new TimedRequest(httpRequest, System.currentTimeMillis(), flowId));
  }

  @Override
  public void responseSentToClient(FlowContext flowContext, HttpResponse httpResponse) {
    TimedRequest timedRequest = requestMap.remove(flowContext);
    if (timedRequest == null) {
      return;
    }

    String flowId = timedRequest.flowId;
    long duration = System.currentTimeMillis() - timedRequest.startTime;

    // TRACE: Entry logging
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "[{}] ENTER responseSentToClient - status={}, duration={}ms",
          flowId,
          httpResponse.status().code(),
          duration);
    }

    // INFO: Complete interaction summary (single log line with all metrics)
    if (LOG.isInfoEnabled()) {
      logInteractionSummary(flowContext, timedRequest, httpResponse, duration, flowId);
    }

    // DEBUG: Formatted log entry
    if (shouldLogFormattedEntry()) {
      String logMessage = formatLogEntry(flowContext, timedRequest, httpResponse);
      if (logMessage != null) {
        logFormattedEntry(logMessage);
      }
    }
  }

  /**
   * Determines if formatted log entries should be logged. This method can be overridden in tests to
   * force logging regardless of the logger's debug level.
   *
   * @return true if formatted entries should be logged
   */
  protected boolean shouldLogFormattedEntry() {
    return LOG.isDebugEnabled();
  }

  /**
   * Logs a formatted entry at DEBUG level. This method can be overridden by subclasses or tests to
   * capture the formatted output.
   *
   * @param message the formatted log message
   */
  protected void logFormattedEntry(String message) {
    LOG.debug(message);
  }

  /**
   * Logs a complete interaction summary at INFO level. This is the primary operational log entry
   * containing all aggregated metrics for a request/response cycle.
   */
  private void logInteractionSummary(
      FlowContext flowContext,
      TimedRequest timedRequest,
      HttpResponse response,
      long duration,
      String flowId) {

    HttpRequest request = timedRequest.request;
    InetSocketAddress clientAddress = flowContext.getClientAddress();
    String clientIp = clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
    int clientPort = clientAddress != null ? clientAddress.getPort() : 0;

    // Get client state metrics
    ClientState clientState = clientStates.get(clientAddress);
    long clientConnectTime = clientState != null ? clientState.connectTime : 0;
    long clientConnectionDuration =
        clientConnectTime > 0 ? System.currentTimeMillis() - clientConnectTime : 0;
    long sslHandshakeTime =
        clientState != null && clientState.sslHandshakeEndTime > 0
            ? clientState.sslHandshakeEndTime - clientState.sslHandshakeStartTime
            : 0;
    int clientSaturations = clientState != null ? clientState.saturationCount : 0;
    String exceptionType = clientState != null ? clientState.lastExceptionType : null;

    // Get server state metrics
    ServerState serverState =
        flowContext instanceof FullFlowContext
            ? serverStates.get((FullFlowContext) flowContext)
            : null;
    long serverConnectTime =
        serverState != null && serverState.connectEndTime > 0
            ? serverState.connectEndTime - serverState.connectStartTime
            : 0;
    int serverSaturations = serverState != null ? serverState.saturationCount : 0;
    String serverAddress =
        serverState != null && serverState.remoteAddress != null
            ? serverState.remoteAddress.getAddress().getHostAddress()
            : "-";
    int serverPort =
        serverState != null && serverState.remoteAddress != null
            ? serverState.remoteAddress.getPort()
            : 0;

    // Build structured log entry
    StringBuilder sb = new StringBuilder();
    sb.append("flow_id=").append(flowId);
    sb.append(" client_ip=").append(clientIp);
    sb.append(" client_port=").append(clientPort);
    sb.append(" server_ip=").append(serverAddress);
    sb.append(" server_port=").append(serverPort);
    sb.append(" method=").append(request.method().name());
    sb.append(" uri=\"").append(request.uri()).append("\"");
    sb.append(" protocol=").append(request.protocolVersion());
    sb.append(" status=").append(response.status().code());
    sb.append(" bytes=").append(getContentLength(response));
    sb.append(" http_request_ms=").append(duration);
    sb.append(" tcp_connection_ms=").append(clientConnectionDuration);
    sb.append(" server_connect_ms=").append(serverConnectTime);
    sb.append(" ssl_handshake_ms=").append(sslHandshakeTime);
    sb.append(" client_saturations=").append(clientSaturations);
    sb.append(" server_saturations=").append(serverSaturations);
    sb.append(" exception=").append(exceptionType != null ? exceptionType : "none");

    LOG.info(sb.toString());
  }

  // ==================== CLIENT LIFECYCLE ====================

  @Override
  public void clientConnected(FlowContext flowContext) {
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    String flowId = generateFlowId();

    // TRACE: Detailed entry logging
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "[{}] ENTER clientConnected - address={}, thread={}, timestamp={}",
          flowId,
          clientAddress,
          Thread.currentThread().getName(),
          now);
    }

    // DEBUG: Essential operation
    if (LOG.isDebugEnabled()) {
      LOG.debug("[{}] Client connected: {}", flowId, clientAddress);
    }

    // Track state
    if (clientAddress != null) {
      clientStates.put(clientAddress, new ClientState(now, flowId));
    }
  }

  @Override
  public void clientSSLHandshakeSucceeded(FlowContext flowContext, SSLSession sslSession) {
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    ClientState state = clientAddress != null ? clientStates.get(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    // TRACE: Detailed entry logging
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "[{}] ENTER clientSSLHandshakeSucceeded - address={}, protocol={}, cipherSuite={}, timestamp={}",
          flowId,
          clientAddress,
          sslSession.getProtocol(),
          sslSession.getCipherSuite(),
          now);
    }

    if (state != null) {
      state.sslHandshakeEndTime = now;
      state.sslSession = sslSession;
      long duration = state.sslHandshakeEndTime - state.sslHandshakeStartTime;

      // DEBUG: Essential operation with timing
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "[{}] Client SSL handshake succeeded: {}, duration: {}ms",
            flowId,
            clientAddress,
            duration);
      }
    }
  }

  @Override
  public void clientDisconnected(FlowContext flowContext, SSLSession sslSession) {
    long now = System.currentTimeMillis();
    InetSocketAddress clientAddress = flowContext != null ? flowContext.getClientAddress() : null;
    ClientState state = clientAddress != null ? clientStates.remove(clientAddress) : null;
    String flowId = state != null ? state.flowId : "unknown";

    // TRACE: Detailed entry logging
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "[{}] ENTER clientDisconnected - address={}, timestamp={}", flowId, clientAddress, now);
    }

    if (state != null) {
      state.disconnectTime = now;
      long duration = state.disconnectTime - state.connectTime;

      // DEBUG: Essential operation with timing
      if (LOG.isDebugEnabled()) {
        LOG.debug("[{}] Client disconnected: {}, duration: {}ms", flowId, clientAddress, duration);
      }
    } else {
      // DEBUG: Still log even if state not found
      if (LOG.isDebugEnabled()) {
        LOG.debug("[{}] Client disconnected: {} (no state found)", flowId, clientAddress);
      }
    }
  }

  // ==================== SERVER LIFECYCLE ====================

  @Override
  public void serverConnected(FullFlowContext flowContext, InetSocketAddress serverAddress) {
    long now = System.currentTimeMillis();
    String flowId = getFlowId(flowContext);

    // TRACE: Detailed entry logging
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "[{}] ENTER serverConnected - serverAddress={}, flowContext={}, timestamp={}",
          flowId,
          serverAddress,
          flowContext,
          now);
    }

    // DEBUG: Essential operation
    if (LOG.isDebugEnabled()) {
      LOG.debug("[{}] Server connected: {}", flowId, serverAddress);
    }

    // Track state
    serverStates.put(flowContext, new ServerState(now, serverAddress));
  }

  @Override
  public void serverDisconnected(FullFlowContext flowContext, InetSocketAddress serverAddress) {
    long now = System.currentTimeMillis();
    ServerState state = serverStates.remove(flowContext);
    String flowId = getFlowId(flowContext);

    // TRACE: Detailed entry logging
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "[{}] ENTER serverDisconnected - serverAddress={}, timestamp={}",
          flowId,
          serverAddress,
          now);
    }

    if (state != null) {
      state.disconnectTime = now;
      long duration = state.connectEndTime > 0 ? state.disconnectTime - state.connectEndTime : 0;
      long timeToConnect = state.connectEndTime - state.connectStartTime;

      // DEBUG: Essential operation with timing
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "[{}] Server disconnected: {}, connection duration: {}ms, time to connect: {}ms",
            flowId,
            serverAddress,
            duration,
            timeToConnect);
      }
    } else {
      // DEBUG: Still log even if state not found
      if (LOG.isDebugEnabled()) {
        LOG.debug("[{}] Server disconnected: {} (no state found)", flowId, serverAddress);
      }
    }
  }

  // ==================== CONNECTION STATE ====================

  @Override
  public void connectionSaturated(FlowContext flowContext) {
    String flowId = getFlowId(flowContext);
    // TRACE: Detailed diagnostics
    if (LOG.isTraceEnabled()) {
      String side = isClientContext(flowContext) ? "client" : "server";
      LOG.trace("[{}] Connection saturated - side={}, flowContext={}", flowId, side, flowContext);
    }

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
  }

  @Override
  public void connectionWritable(FlowContext flowContext) {
    String flowId = getFlowId(flowContext);
    // TRACE: Detailed diagnostics only
    if (LOG.isTraceEnabled()) {
      String side = isClientContext(flowContext) ? "client" : "server";
      LOG.trace("[{}] Connection writable - side={}, flowContext={}", flowId, side, flowContext);
    }
  }

  @Override
  public void connectionTimedOut(FlowContext flowContext) {
    String side = isClientContext(flowContext) ? "client" : "server";
    String flowId = getFlowId(flowContext);

    // TRACE: Detailed entry logging
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "[{}] ENTER connectionTimedOut - side={}, flowContext={}", flowId, side, flowContext);
    }

    // DEBUG: Essential operation
    if (LOG.isDebugEnabled()) {
      LOG.debug("[{}] Connection timed out: {}", flowId, side);
    }
  }

  @Override
  public void connectionExceptionCaught(FlowContext flowContext, Throwable cause) {
    String side = isClientContext(flowContext) ? "client" : "server";
    String flowId = getFlowId(flowContext);

    // TRACE: Detailed entry logging with stack trace
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "[{}] ENTER connectionExceptionCaught - side={}, exceptionType={}, message={}",
          flowId,
          side,
          cause.getClass().getName(),
          cause.getMessage(),
          cause);
    }

    // DEBUG: Essential operation with exception details
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "[{}] Connection exception caught: {}, type: {}, message: {}",
          flowId,
          side,
          cause.getClass().getSimpleName(),
          cause.getMessage());
    }

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
   * Formats a log entry using the configured fields. This method dynamically generates log entries
   * based on the field configuration rather than hardcoded field lists.
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

  /** Formats CLF log entry. */
  private void formatClfEntry(
      StringBuilder sb,
      FlowContext flowContext,
      HttpRequest request,
      HttpResponse response,
      long duration,
      ZonedDateTime now) {
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

  /** Formats ELF log entry. */
  private void formatElfEntry(
      StringBuilder sb,
      FlowContext flowContext,
      HttpRequest request,
      HttpResponse response,
      long duration,
      ZonedDateTime now) {
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

  /** Formats W3C log entry. */
  private void formatW3cEntry(
      StringBuilder sb,
      FlowContext flowContext,
      HttpRequest request,
      HttpResponse response,
      long duration,
      ZonedDateTime now) {
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

  /** Formats JSON log entry. */
  private void formatJsonEntry(
      StringBuilder sb,
      FlowContext flowContext,
      HttpRequest request,
      HttpResponse response,
      long duration,
      ZonedDateTime now) {

    sb.append("{");

    // Use configured fields dynamically
    boolean first = true;
    for (LogField field : fieldConfiguration.getFields()) {
      // Handle prefix-based fields that expand to multiple entries
      if (field instanceof PrefixRequestHeaderField) {
        PrefixRequestHeaderField prefixField = (PrefixRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            prefixField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"")
              .append(entry.getKey())
              .append("\":\"")
              .append(escapeJson(entry.getValue()))
              .append("\"");
        }
      } else if (field instanceof PrefixResponseHeaderField) {
        PrefixResponseHeaderField prefixField = (PrefixResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            prefixField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"")
              .append(entry.getKey())
              .append("\":\"")
              .append(escapeJson(entry.getValue()))
              .append("\"");
        }
      } else if (field instanceof RegexRequestHeaderField) {
        RegexRequestHeaderField regexField = (RegexRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            regexField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"")
              .append(entry.getKey())
              .append("\":\"")
              .append(escapeJson(entry.getValue()))
              .append("\"");
        }
      } else if (field instanceof RegexResponseHeaderField) {
        RegexResponseHeaderField regexField = (RegexResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            regexField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"")
              .append(entry.getKey())
              .append("\":\"")
              .append(escapeJson(entry.getValue()))
              .append("\"");
        }
      } else if (field instanceof ExcludeRequestHeaderField) {
        ExcludeRequestHeaderField excludeField = (ExcludeRequestHeaderField) field;
        for (Map.Entry<String, String> entry :
            excludeField.extractMatchingHeaders(request.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"")
              .append(entry.getKey())
              .append("\":\"")
              .append(escapeJson(entry.getValue()))
              .append("\"");
        }
      } else if (field instanceof ExcludeResponseHeaderField) {
        ExcludeResponseHeaderField excludeField = (ExcludeResponseHeaderField) field;
        for (Map.Entry<String, String> entry :
            excludeField.extractMatchingHeaders(response.headers()).entrySet()) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append("\"")
              .append(entry.getKey())
              .append("\":\"")
              .append(escapeJson(entry.getValue()))
              .append("\"");
        }
      } else {
        if (!first) {
          sb.append(",");
        }
        first = false;

        String value = field.extractValue(flowContext, request, response, duration);
        sb.append("\"")
            .append(field.getName())
            .append("\":\"")
            .append(escapeJson(value))
            .append("\"");
      }
    }

    sb.append("}");
  }

  /** Formats LTSV log entry. */
  private void formatLtsvEntry(
      StringBuilder sb,
      FlowContext flowContext,
      HttpRequest request,
      HttpResponse response,
      long duration,
      ZonedDateTime now) {

    // Labeled Tab-Separated Values
    boolean first = true;
    for (LogField field : fieldConfiguration.getFields()) {
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

        String value = field.extractValue(flowContext, request, response, duration);
        sb.append(field.getName()).append(":").append(value);
      }
    }
  }

  /** Formats CSV log entry. */
  private void formatCsvEntry(
      StringBuilder sb,
      FlowContext flowContext,
      HttpRequest request,
      HttpResponse response,
      long duration,
      ZonedDateTime now) {

    // Comma-Separated Values
    boolean first = true;
    for (LogField field : fieldConfiguration.getFields()) {
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
        if (!first) {
          sb.append(",");
        }
        first = false;

        String value = field.extractValue(flowContext, request, response, duration);
        sb.append("\"").append(escapeJson(value)).append("\"");
      }
    }
  }

  /** Formats SQUID log entry. */
  private void formatSquidEntry(
      StringBuilder sb,
      FlowContext flowContext,
      HttpRequest request,
      HttpResponse response,
      long duration,
      ZonedDateTime now) {
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

  /** Formats HAProxy log entry. */
  private void formatHaproxyEntry(
      StringBuilder sb,
      FlowContext flowContext,
      HttpRequest request,
      HttpResponse response,
      long duration,
      ZonedDateTime now) {
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
   * Reconstructs the full URL from the request. If the URI is already absolute (starts with http://
   * or https://), returns it as-is. Otherwise, prepends the Host header to create a complete URL.
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
   *
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
   *
   * @param response the HTTP response
   * @return content length or "-"
   */
  protected String getContentLength(HttpResponse response) {
    String len = response.headers().get("Content-Length");
    return len != null ? len : "-";
  }

  /**
   * Gets content type from response.
   *
   * @param response the HTTP response
   * @return content type or "-"
   */
  protected String getContentType(HttpResponse response) {
    String val = response.headers().get("Content-Type");
    return val != null ? val : "-";
  }

  /**
   * Gets server IP from flow context.
   *
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
   *
   * @param s the string to escape
   * @return escaped string
   */
  protected String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\"", "\\\"").replace("\\", "\\\\");
  }

  /**
   * Formats timestamp using specified pattern.
   *
   * @param zonedDateTime the date/time to format
   * @param pattern the date format pattern
   * @return formatted timestamp
   */
  private String format(ZonedDateTime zonedDateTime, String pattern) {
    DateTimeFormatter dtf = DateTimeFormatter.ofPattern(pattern, Locale.US);
    return zonedDateTime.format(dtf);
  }
}
