package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.ZonedDateTime;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

/**
 * Formatter for Common Log Format (CLF).
 *
 * <p>CLF format: host ident authuser [date] "request" status bytes
 *
 * <p>Example: 127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] "GET /apache_pb.gif HTTP/1.0" 200 2326
 */
public class ClfFormatter extends AbstractLogEntryFormatter {

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

    return sb.toString();
  }

  @Override
  public LogFormat getSupportedFormat() {
    return LogFormat.CLF;
  }
}
