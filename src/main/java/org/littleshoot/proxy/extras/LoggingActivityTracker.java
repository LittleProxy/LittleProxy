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
    private final Map<FlowContext, HttpRequest> requestMap = new ConcurrentHashMap<>();

    public LoggingActivityTracker(LogFormat logFormat) {
        this.logFormat = logFormat;
    }

    @Override
    public void requestReceivedFromClient(FlowContext flowContext, HttpRequest httpRequest) {
        requestMap.put(flowContext, httpRequest);
    }

    @Override
    public void responseSentToClient(FlowContext flowContext, HttpResponse httpResponse) {
        HttpRequest request = requestMap.remove(flowContext);
        if (request == null) {
            // Should not happen usually, but possible if tracker added mid-flight or odd
            // flow
            return;
        }

        String logMessage = formatLogEntry(flowContext, request, httpResponse);
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
        // But the map keys are FlowContext objects.
        // FlowContext doesn't implement equals/hashCode based on address, but checking
        // identity.
        // Ideally we should use a WeakHashMap or similar if leaks are concern,
        // but FlowContext should be short lived enough or we rely on responseSent to
        // clear.
        // For now, rely on responseSentToClient to clear.
        // If a request is received but no response sent (disconnect), it might leak.
        // But ActivityTracker doesn't seem to offer a "Flow Ended" clearly linking to
        // FlowContext for disconnect.
        // We'll leave it simple for now.
    }

    private String formatLogEntry(FlowContext flowContext, HttpRequest request, HttpResponse response) {
        StringBuilder sb = new StringBuilder();
        InetSocketAddress clientAddress = flowContext.getClientAddress();
        String clientIp = clientAddress != null ? clientAddress.getAddress().getHostAddress() : "-";
        Date now = new Date();

        switch (logFormat) {
            case CLF:
                // host ident authuser [date] "request" status bytes
                sb.append(clientIp).append(" ");
                sb.append("- "); // ident
                sb.append("- "); // authuser (could try to extract from Auth header if needed)
                sb.append("[").append(formatDate(now, DATE_FORMAT_CLF)).append("] ");
                sb.append("\"").append(request.method()).append(" ").append(request.uri()).append(" ")
                        .append(request.protocolVersion()).append("\" ");
                sb.append(response.status().code()).append(" ");
                sb.append(getContentLength(response));
                break;

            case ELF:
            case W3C:
                // W3C Extended Log Format (simplified default)
                // date time c-ip cs-method cs-uri-stem sc-status sc-bytes
                // time-taken(optional/unavailable) cs(User-Agent)
                // We'll approximate a standard extended format.
                // 2023-10-25 12:00:00 127.0.0.1 GET /index.html 200 1234 "User-Agent"
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
                sb.append("\"user_agent\":\"").append(escapeJson(getHeader(request, "User-Agent"))).append("\"");
                sb.append("}");
                break;

            case SQUID:
                // time elapsed remotehost code/status bytes method URL rfc931
                // peerstatus/peerhost type
                // time is unix timestamp with millis
                // elapsed is missing in this context effectively (would need to track request
                // time).
                long timestamp = now.getTime();
                sb.append(timestamp / 1000).append(".").append(timestamp % 1000).append(" ");
                sb.append("0 "); // elapsed placeholder
                sb.append(clientIp).append(" ");
                sb.append("TCP_MISS/").append(response.status().code()).append(" ");
                sb.append(getContentLength(response)).append(" ");
                sb.append(request.method()).append(" ");
                sb.append(request.uri()).append(" ");
                sb.append("- "); // rfc931
                sb.append("DIRECT/").append(getServerIp(flowContext)).append(" ");
                sb.append(getContentType(response));
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
