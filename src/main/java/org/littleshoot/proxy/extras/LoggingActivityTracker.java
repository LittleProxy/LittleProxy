package org.littleshoot.proxy.extras;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.littleshoot.proxy.ActivityTrackerAdapter;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link org.littleshoot.proxy.ActivityTracker} that logs HTTP activity.
 */
public class LoggingActivityTracker extends ActivityTrackerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingActivityTracker.class);
    private static final String DATE_FORMAT_CLF = "dd/MMM/yyyy:HH:mm:ss Z";

    private final LogFormat logFormat;

    private static class TimedRequest {
        final HttpRequest request;
        final long startTime;

        TimedRequest(HttpRequest request, long startTime) {
            this.request = request;
            this.startTime = startTime;
        }
    }

    private final Map<FlowContext, TimedRequest> requestMap = new ConcurrentHashMap<>();

    public LoggingActivityTracker(LogFormat logFormat) {
        this.logFormat = logFormat;
    }

    @Override
    public void requestReceivedFromClient(FlowContext flowContext, HttpRequest httpRequest) {
        requestMap.put(flowContext, new TimedRequest(httpRequest, System.currentTimeMillis()));
    }

    @Override
    public void responseSentToClient(FlowContext flowContext, HttpResponse httpResponse) {
        TimedRequest timedRequest = requestMap.remove(flowContext);
        if (timedRequest == null) {
            return;
        }

        String logMessage = formatLogEntry(flowContext, timedRequest, httpResponse);
        if (logMessage != null) {
            log(logMessage);
        }
    }

    protected void log(String message) {
        LOG.info(message);
    }

    // Cleanup on disconnect just in case
    @Override
    public void clientDisconnected(InetSocketAddress clientAddress, javax.net.ssl.SSLSession sslSession) {
        // We can't easily clean up by FlowContext here as we only have address/session.
        // For now, rely on responseSentToClient to clear.
    }

    private String formatLogEntry(FlowContext flowContext, TimedRequest timedInfo, HttpResponse response) {
        HttpRequest request = timedInfo.request;
        long duration = System.currentTimeMillis() - timedInfo.startTime;

        StringBuilder sb = new StringBuilder();
        InetSocketAddress clientAddress = flowContext.getClientAddress();
        String clientIp = clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
        Date now = new Date();

        switch (logFormat) {
            case CLF:
                // host ident authuser [date] "request" status bytes
                sb.append(clientIp).append(" ");
                sb.append("- "); // ident
                sb.append("- "); // authuser
                sb.append("[").append(formatDate(now, DATE_FORMAT_CLF)).append("] ");
                sb.append("\"").append(request.method()).append(" ").append(request.uri()).append(" ")
                        .append(request.protocolVersion()).append("\" ");
                sb.append(response.status().code()).append(" ");
                sb.append(getContentLength(response));
                break;

            case ELF:
                // Extended Log Format (ELF) - actually NCSA Combined Log Format
                // host ident authuser [date] "request" status bytes "referer" "user-agent"
                sb.append(clientIp).append(" ");
                sb.append("- "); // ident
                sb.append("- "); // authuser
                sb.append("[").append(formatDate(now, DATE_FORMAT_CLF)).append("] ");
                sb.append("\"").append(request.method()).append(" ").append(request.uri()).append(" ")
                        .append(request.protocolVersion()).append("\" ");
                sb.append(response.status().code()).append(" ");
                sb.append(getContentLength(response)).append(" ");
                sb.append("\"").append(getHeader(request, "Referer")).append("\" ");
                sb.append("\"").append(getHeader(request, "User-Agent")).append("\"");
                break;

            case W3C:
                // W3C Extended Log Format (simplified default)
                // date time c-ip cs-method cs-uri-stem sc-status sc-bytes
                // time-taken(optional/unavailable) cs(User-Agent)
                SimpleDateFormat w3cDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                w3cDate.setTimeZone(TimeZone.getTimeZone("UTC"));
                sb.append(w3cDate.format(now)).append(" ");
                sb.append(clientIp).append(" ");
                sb.append(request.method()).append(" ");
                sb.append(request.uri()).append(" ");
                sb.append(response.status().code()).append(" ");
                sb.append(getContentLength(response)).append(" ");
                sb.append("\"").append(getHeader(request, "User-Agent")).append("\"");
                break;

            case JSON:
                sb.append("{");
                sb.append("\"timestamp\":\"").append(formatDate(now, "yyyy-MM-dd'T'HH:mm:ss.SSSZ")).append("\",");
                sb.append("\"client_ip\":\"").append(clientIp).append("\",");
                sb.append("\"method\":\"").append(request.method()).append("\",");
                sb.append("\"uri\":\"").append(escapeJson(request.uri())).append("\",");
                sb.append("\"protocol\":\"").append(request.protocolVersion()).append("\",");
                sb.append("\"status\":").append(response.status().code()).append(",");
                sb.append("\"bytes\":").append(getContentLength(response)).append(",");
                sb.append("\"duration\":").append(duration).append(",");
                sb.append("\"user_agent\":\"").append(escapeJson(getHeader(request, "User-Agent"))).append("\"");
                sb.append("}");
                break;

            case LTSV:
                // Labeled Tab-Separated Values
                sb.append("time:").append(formatDate(now, "yyyy-MM-dd'T'HH:mm:ss.SSSZ")).append("\t");
                sb.append("host:").append(clientIp).append("\t");
                sb.append("method:").append(request.method()).append("\t");
                sb.append("uri:").append(request.uri()).append("\t");
                sb.append("status:").append(response.status().code()).append("\t");
                sb.append("size:").append(getContentLength(response)).append("\t");
                sb.append("duration:").append(duration).append("\t");
                sb.append("ua:").append(getHeader(request, "User-Agent"));
                break;

            case CSV:
                // Comma-Separated Values: timestamp,host,method,uri,status,bytes,duration,ua
                sb.append("\"").append(formatDate(now, "yyyy-MM-dd'T'HH:mm:ss.SSSZ")).append("\",");
                sb.append("\"").append(clientIp).append("\",");
                sb.append("\"").append(request.method()).append("\",");
                sb.append("\"").append(escapeJson(request.uri())).append("\",");
                sb.append(response.status().code()).append(",");
                sb.append(getContentLength(response)).append(",");
                sb.append(duration).append(",");
                sb.append("\"").append(escapeJson(getHeader(request, "User-Agent"))).append("\"");
                break;

            case SQUID:
                // time elapsed remotehost code/status bytes method URL rfc931
                // peerstatus/peerhost type
                long timestamp = now.getTime();
                sb.append(timestamp / 1000).append(".").append(timestamp % 1000).append(" ");
                sb.append(duration).append(" "); // elapsed
                sb.append(clientIp).append(" ");
                sb.append("TCP_MISS/").append(response.status().code()).append(" ");
                sb.append(getContentLength(response)).append(" ");
                sb.append(request.method()).append(" ");
                sb.append(request.uri()).append(" ");
                sb.append("- "); // rfc931
                sb.append("DIRECT/").append(getServerIp(flowContext)).append(" ");
                sb.append(getContentType(response));
                break;

            case HAPROXY:
                // HAProxy HTTP format approximation
                // client_ip:port [date] frontend backend/server Tq Tw Tc Tr Tr_tot status bytes
                // ...
                // simplified: client_ip [date] method uri status bytes duration
                sb.append(clientIp).append(" ");
                sb.append("[").append(formatDate(now, "dd/MMM/yyyy:HH:mm:ss.SSS")).append("] ");
                sb.append("\"").append(request.method()).append(" ").append(request.uri()).append(" ")
                        .append(request.protocolVersion()).append("\" ");
                sb.append(response.status().code()).append(" ");
                sb.append(getContentLength(response)).append(" ");
                sb.append(duration); // duration in ms
                break;
        }

        return sb.toString();
    }

    private String formatDate(Date date, String pattern) {
        SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
        return sdf.format(date);
    }

    private String getContentLength(HttpResponse response) {
        String len = response.headers().get("Content-Length");
        return len != null ? len : "-";
    }

    private String getHeader(HttpRequest request, String headerName) {
        String val = request.headers().get(headerName);
        return val != null ? val : "-";
    }

    private String getContentType(HttpResponse response) {
        String val = response.headers().get("Content-Type");
        return val != null ? val : "-";
    }

    private String getServerIp(FlowContext context) {
        if (context instanceof FullFlowContext) {
            String hostAndPort = ((FullFlowContext) context).getServerHostAndPort();
            if (hostAndPort != null) {
                // Returns "host:port", we want just the host/ip usually, or stick with
                // host:port?
                // Squid format usually asks for remotehost or peerhost.
                // We will return request host.
                return hostAndPort.split(":")[0];
            }
        }
        return "-";
    }

    private String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\"", "\\\"").replace("\\", "\\\\");
    }
}
