package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.ZonedDateTime;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

/**
 * Formatter for HAProxy HTTP log format.
 *
 * <p>HAProxy format includes detailed timing information and connection details.
 *
 * <p>Example: littleproxy[0]: 127.0.0.1:12345 [10/Oct/2000:13:55:36.123] frontend backend/server
 * 0/0/0/0/500 200 1234 - - - - - - - "GET http://example.com/path HTTP/1.1"
 */
public class HaproxyFormatter extends AbstractLogEntryFormatter {

  @Override
  public String format(
      FlowContext context,
      HttpRequest request,
      HttpResponse response,
      ZonedDateTime now,
      String flowId,
      LogFieldConfiguration fieldConfig) {

    StringBuilder sb = new StringBuilder();
    String clientIp = getClientIp(context);
    int clientPort = getClientPort(context);

    // Get timing data from flow context
    String durationMs = getTimingData(context, "http_request_processing_time");

    // HAProxy HTTP log format:
    // process[pid]: client_ip:port [accept_date] frontend backend/server Tq Tw Tc Tr Ta status
    // bytes
    // cc cs sc rc sr st rt request
    // Since LittleProxy doesn't have frontend/backend/server separation, we use simplified format
    sb.append("littleproxy[0]: "); // process name and pid
    sb.append(clientIp).append(":").append(clientPort).append(" ");
    sb.append("[").append(format(now, "dd/MMM/yyyy:HH:mm:ss.SSS")).append("] ");
    sb.append("frontend backend/server "); // placeholder for frontend/backend/server
    sb.append("0/0/0/0/").append(durationMs).append(" "); // Tq/Tw/Tc/Tr/Ta timing
    sb.append(response.status().code()).append(" ");
    sb.append(getContentLength(response)).append(" ");
    sb.append("- - - - - - - "); // cc cs sc rc sr st rt (counters and termination state)
    sb.append("\"")
        .append(request.method())
        .append(" ")
        .append(getFullUrl(request))
        .append(" ")
        .append(request.protocolVersion())
        .append("\"");

    return sb.toString();
  }

  @Override
  public LogFormat getSupportedFormat() {
    return LogFormat.HAPROXY;
  }
}
