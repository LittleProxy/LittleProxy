package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import java.util.Map;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

/**
 * Formatter for Key-Value structured text format.
 *
 * <p>Produces human-readable key=value pairs with flow and connection metrics.
 *
 * <p>Example: flow_id=abc123 client_ip=127.0.0.1 client_port=12345 method=GET uri="/path"
 * status=200 bytes=1234
 */
public class KeyValueFormatter extends AbstractLogEntryFormatter {

  @Override
  public String format(
      FlowContext context,
      HttpRequest request,
      HttpResponse response,
      ZonedDateTime now,
      String flowId,
      LogFieldConfiguration fieldConfig) {

    StringBuilder sb = new StringBuilder();
    InetSocketAddress clientAddress = context.getClientAddress();
    String clientIp = clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
    int clientPort = clientAddress != null ? clientAddress.getPort() : 0;

    // Get server information
    String serverAddress = "-";
    int serverPort = 0;
    if (context instanceof FullFlowContext) {
      String hostAndPort = ((FullFlowContext) context).getServerHostAndPort();
      if (hostAndPort != null) {
        String[] parts = hostAndPort.split(":");
        serverAddress = parts[0];
        serverPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
      }
    }

    // Get timing data from flow context
    String httpRequestMs = getTimingData(context, "http_request_processing_time_ms");

    // Build structured log entry: flow_id=... client_ip=... key=value pairs
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
    sb.append(" http_request_ms=").append(httpRequestMs);

    return sb.toString();
  }

  @Override
  public LogFormat getSupportedFormat() {
    return LogFormat.KEYVALUE;
  }

  @Override
  public String formatLifecycleEvent(
      LifecycleEvent event, FlowContext context, Map<String, Object> attributes, String flowId) {
    StringBuilder sb = new StringBuilder();
    sb.append("flow_id=").append(flowId);
    sb.append(" event=").append(event.getEventName());
    sb.append(" client_ip=").append(getClientIp(context));

    // Add all event-specific attributes
    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
      sb.append(" ").append(entry.getKey()).append("=").append(entry.getValue());
    }

    return sb.toString();
  }
}
