package org.littleshoot.proxy.extras;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggingActivityTrackerTest {

    @Mock
    private FlowContext flowContext;
    @Mock
    private FullFlowContext fullFlowContext;
    @Mock
    private HttpRequest request;
    @Mock
    private HttpResponse response;
    @Mock
    private HttpHeaders requestHeaders;
    @Mock
    private HttpHeaders responseHeaders;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(request.headers()).thenReturn(requestHeaders);
        when(response.headers()).thenReturn(responseHeaders);
    }

    @Test
    void testClfFormat() {
        TestableLoggingActivityTracker tracker = new TestableLoggingActivityTracker(LogFormat.CLF);
        setupMocks();

        tracker.requestReceivedFromClient(flowContext, request);
        tracker.responseSentToClient(flowContext, response);

        System.out.println("CLF Log: " + tracker.lastLogMessage);
        // Expecting: 127.0.0.1 - - [Date] "GET /test HTTP/1.1" 200 100
        assertTrue(tracker.lastLogMessage.contains("127.0.0.1 - - ["));
        assertTrue(tracker.lastLogMessage.contains("] \"GET /test HTTP/1.1\" 200 100"));
    }

    @Test
    void testJsonFormat() {
        TestableLoggingActivityTracker tracker = new TestableLoggingActivityTracker(LogFormat.JSON);
        setupMocks();

        tracker.requestReceivedFromClient(flowContext, request);
        tracker.responseSentToClient(flowContext, response);

        System.out.println("JSON Log: " + tracker.lastLogMessage);
        assertTrue(tracker.lastLogMessage.startsWith("{"));
        assertTrue(tracker.lastLogMessage.contains("\"client_ip\":\"127.0.0.1\""));
        assertTrue(tracker.lastLogMessage.contains("\"method\":\"GET\""));
        assertTrue(tracker.lastLogMessage.contains("\"uri\":\"/test\""));
        assertTrue(tracker.lastLogMessage.contains("\"status\":200"));
        assertTrue(tracker.lastLogMessage.contains("\"bytes\":100"));
    }

    @Test
    void testElfFormat() {
        TestableLoggingActivityTracker tracker = new TestableLoggingActivityTracker(LogFormat.ELF);
        setupMocks();
        when(requestHeaders.get("Referer")).thenReturn("http://referrer.com");
        when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");

        tracker.requestReceivedFromClient(flowContext, request);
        tracker.responseSentToClient(flowContext, response);

        System.out.println("ELF Log: " + tracker.lastLogMessage);
        // host ident authuser [date] "request" status bytes "referer" "user-agent"
        // 127.0.0.1 - - [Date] "GET /test HTTP/1.1" 200 100 "http://referrer.com"
        // "Mozilla/5.0"
        assertTrue(tracker.lastLogMessage.startsWith("127.0.0.1 - - ["));
        assertTrue(tracker.lastLogMessage
                .contains("] \"GET /test HTTP/1.1\" 200 100 \"http://referrer.com\" \"Mozilla/5.0\""));
    }

    @Test
    void testW3cFormat() {
        TestableLoggingActivityTracker tracker = new TestableLoggingActivityTracker(LogFormat.W3C);
        setupMocks();
        when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");

        tracker.requestReceivedFromClient(flowContext, request);
        tracker.responseSentToClient(flowContext, response);

        System.out.println("W3C Log: " + tracker.lastLogMessage);
        // date time c-ip cs-method cs-uri-stem sc-status sc-bytes cs(User-Agent)
        // YYYY-MM-DD HH:MM:SS 127.0.0.1 GET /test 200 100 "Mozilla/5.0"
        assertTrue(tracker.lastLogMessage.contains(" 127.0.0.1 GET /test 200 100 \"Mozilla/5.0\""));
    }

    @Test
    void testLtsvFormat() {
        TestableLoggingActivityTracker tracker = new TestableLoggingActivityTracker(LogFormat.LTSV);
        setupMocksWithDelay();

        tracker.requestReceivedFromClient(flowContext, request);
        // Simulate delay
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }
        tracker.responseSentToClient(flowContext, response);

        System.out.println("LTSV Log: " + tracker.lastLogMessage);
        // time:... host:127.0.0.1 method:GET uri:/test status:200 size:100 duration:>=0
        // ua:Mozilla/5.0
        assertTrue(tracker.lastLogMessage
                .contains("host:127.0.0.1\tmethod:GET\turi:/test\tstatus:200\tsize:100\tduration:"));
    }

    @Test
    void testCsvFormat() {
        TestableLoggingActivityTracker tracker = new TestableLoggingActivityTracker(LogFormat.CSV);
        setupMocksWithDelay();

        tracker.requestReceivedFromClient(flowContext, request);
        tracker.responseSentToClient(flowContext, response);

        System.out.println("CSV Log: " + tracker.lastLogMessage);
        // "timestamp","127.0.0.1","GET","/test",200,100,duration,"Mozilla/5.0"
        assertTrue(tracker.lastLogMessage.contains("\",\"127.0.0.1\",\"GET\",\"/test\",200,100,"));
        assertTrue(tracker.lastLogMessage.endsWith(",\"Mozilla/5.0\""));
    }

    @Test
    void testHaproxyFormat() {
        TestableLoggingActivityTracker tracker = new TestableLoggingActivityTracker(LogFormat.HAPROXY);
        setupMocksWithDelay();

        tracker.requestReceivedFromClient(flowContext, request);
        tracker.responseSentToClient(flowContext, response);

        System.out.println("HAProxy Log: " + tracker.lastLogMessage);
        // 127.0.0.1 [date] "GET /test HTTP/1.1" 200 100 duration
        assertTrue(tracker.lastLogMessage.startsWith("127.0.0.1 ["));
        assertTrue(tracker.lastLogMessage.contains("] \"GET /test HTTP/1.1\" 200 100 "));
    }

    @Test
    void testSquidFormat() {
        TestableLoggingActivityTracker tracker = new TestableLoggingActivityTracker(LogFormat.SQUID);
        setupMocks();

        tracker.requestReceivedFromClient(flowContext, request);
        tracker.responseSentToClient(flowContext, response);

        System.out.println("Squid Log: " + tracker.lastLogMessage);
        // time elapsed remotehost code/status bytes method URL rfc931
        // peerstatus/peerhost type
        // Check that elapsed time is present (we can't check exact value easily but
        // check structure)
        // 1234567890.123 0 127.0.0.1 ...
        // We now expect something >= 0, not necessarily hardcoded 0.
        // Regex: timestamp space duration space ip ...
        assertTrue(tracker.lastLogMessage
                .matches(".*\\d+ \\d+ 127\\.0\\.0\\.1 TCP_MISS/200 100 GET /test - DIRECT/- -.*"));
    }

    private static class TestableLoggingActivityTracker extends LoggingActivityTracker {
        String lastLogMessage;

        public TestableLoggingActivityTracker(LogFormat logFormat) {
            super(logFormat);
        }

        @Override
        protected void log(String message) {
            this.lastLogMessage = message;
        }
    }

    private void setupMocks() {
        setupMocksCommon();
    }

    private void setupMocksWithDelay() {
        setupMocksCommon();
        when(requestHeaders.get("User-Agent")).thenReturn("Mozilla/5.0");
    }

    private void setupMocksCommon() {
        InetSocketAddress clientAddr = mock(InetSocketAddress.class);
        InetAddress inetAddr = mock(InetAddress.class);
        when(flowContext.getClientAddress()).thenReturn(clientAddr);
        when(clientAddr.getAddress()).thenReturn(inetAddr);
        when(inetAddr.getHostAddress()).thenReturn("127.0.0.1");

        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("/test");
        when(request.protocolVersion()).thenReturn(HttpVersion.HTTP_1_1);

        when(response.status()).thenReturn(HttpResponseStatus.OK);
        when(responseHeaders.get("Content-Length")).thenReturn("100");
    }
}
