package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

/**
 * Formatter for W3C Extended Log File Format.
 *
 * <p>W3C format: date time c-ip cs-method cs-uri-stem sc-status sc-bytes cs(User-Agent)
 *
 * <p>Example: 2000-10-10 13:55:36 127.0.0.1 GET /apache_pb.gif 200 2326 "Mozilla/4.0"
 */
public class W3cFormatter extends AbstractLogEntryFormatter {

  private static final DateTimeFormatter W3C_DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);

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

    // W3C: date time c-ip cs-method cs-uri-stem sc-status sc-bytes cs(User-Agent)
    // cs-uri-stem should be just the path, not full URL
    sb.append(now.format(W3C_DATE_TIME_FORMATTER)).append(" ");
    sb.append(clientIp).append(" ");
    sb.append(request.method()).append(" ");
    sb.append(getUriStem(request)).append(" ");
    sb.append(response.status().code()).append(" ");
    sb.append(getContentLength(response)).append(" ");
    sb.append("\"").append(getHeader(request, USER_AGENT)).append("\"");

    return sb.toString();
  }

  @Override
  public LogFormat getSupportedFormat() {
    return LogFormat.W3C;
  }
}
