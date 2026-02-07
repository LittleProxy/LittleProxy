package org.littleshoot.proxy.extras.logging.formatter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.ZonedDateTime;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;

/**
 * Formatter for Squid Native access log format.
 *
 * <p>Squid format: time elapsed remotehost code/status bytes method URL rfc931 peerstatus/peerhost
 * type
 *
 * <p>Example: 1234567890.123 500 127.0.0.1 TCP_HIT/200 2326 GET http://example.com/path - DIRECT/
 * 10.0.0.1 text/html
 */
public class SquidFormatter extends AbstractLogEntryFormatter {

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

    // Get timing data from flow context
    String durationMs = getTimingData(context, "http_request_processing_time_ms");

    // Squid format: time elapsed remotehost code/status bytes method URL rfc931 peerstatus/peerhost
    // type
    // Time is seconds since epoch with milliseconds (e.g., 1234567890.123)
    long epochSeconds = now.toEpochSecond();
    long millis = now.getNano() / 1_000_000;
    sb.append(epochSeconds).append(".").append(String.format("%03d", millis)).append(" ");
    sb.append(durationMs).append(" "); // elapsed time in ms
    sb.append(clientIp).append(" ");
    // Determine cache result code based on response
    String cacheResult = determineCacheResult(response);
    sb.append(cacheResult).append("/").append(response.status().code()).append(" ");
    sb.append(getContentLength(response)).append(" ");
    sb.append(request.method()).append(" ");
    sb.append(getFullUrl(request)).append(" ");
    sb.append("- "); // rfc931 (ident)
    sb.append("DIRECT/").append(getServerIp(context)).append(" ");
    sb.append(getContentType(response));

    return sb.toString();
  }

  /**
   * Determines the cache result code for Squid format based on response. Returns TCP_HIT for
   * 2xx/3xx status codes, TCP_MISS for others.
   *
   * @param response the HTTP response
   * @return cache result code (TCP_HIT or TCP_MISS)
   */
  private String determineCacheResult(HttpResponse response) {
    int status = response.status().code();
    // 2xx and 3xx are generally considered cacheable hits
    if (status >= 200 && status < 400) {
      return "TCP_HIT";
    }
    return "TCP_MISS";
  }

  @Override
  public LogFormat getSupportedFormat() {
    return LogFormat.SQUID;
  }
}
