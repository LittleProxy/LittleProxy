package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Integration tests for ActivityLogger timing fields using WireMock. These tests verify that TCP
 * timing fields are properly populated (or omitted) in HTTP logs under real proxy scenarios.
 */
@Tag("integration")
class ActivityLoggerTimingIntegrationTest {

  private WireMockServer wireMockServer;
  private HttpProxyServer proxyServer;
  private List<String> capturedLogMessages;

  @BeforeEach
  void setUp() {
    capturedLogMessages = new CopyOnWriteArrayList<>();

    // Start WireMock server
    wireMockServer =
        new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort().dynamicHttpsPort());
    wireMockServer.start();

    // Set up a simple stub
    wireMockServer.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get("/test")
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withStatus(200)
                    .withBody("Hello, World!")));
  }

  @AfterEach
  void tearDown() {
    if (proxyServer != null) {
      proxyServer.stop();
    }
    if (wireMockServer != null) {
      wireMockServer.stop();
    }
  }

  /** Creates an ActivityLogger that captures log messages for verification. */
  private ActivityLogger createCapturingActivityLogger() {
    return new ActivityLogger(LogFormat.JSON, null, TimingMode.ALL) {
      @Override
      protected void logFormattedEntry(String flowId, String message) {
        capturedLogMessages.add(message);
        super.logFormattedEntry(flowId, message);
      }
    };
  }

  @Test
  @Tag("integration")
  void testHttpRequestLogsWithoutTcpTimingFields() throws Exception {
    // Start proxy with capturing logger
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .plusActivityTracker(createCapturingActivityLogger())
            .start();

    // Make a simple HTTP request through the proxy
    URL url = new URL("http://localhost:" + wireMockServer.port() + "/test");
    Proxy proxy =
        new Proxy(
            Proxy.Type.HTTP,
            new InetSocketAddress("localhost", proxyServer.getListenAddress().getPort()));

    HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(5000);

    int responseCode = connection.getResponseCode();
    assertThat(responseCode).isEqualTo(200);

    // Read response body
    try (BufferedReader reader =
        new BufferedReader(
            new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
      String response = reader.lines().collect(Collectors.joining("\n"));
      assertThat(response).contains("Hello, World!");
    }

    // Wait a bit for any async logging
    Thread.sleep(100);

    // Verify that we captured at least one log message
    assertThat(capturedLogMessages).isNotEmpty();

    // Get the last log message
    String logMessage = capturedLogMessages.get(capturedLogMessages.size() - 1);
    System.out.println("Captured log: " + logMessage);

    // Verify it's valid JSON
    assertThat(logMessage).startsWith("{");

    // HTTP fields should be present
    assertThat(logMessage).contains("\"method\"");
    assertThat(logMessage).contains("\"status\"");
    assertThat(logMessage).contains("\"uri\"");

    // If TCP timing data is not available, fields should be omitted
    // (They won't be present with value "-" or as empty strings)
    if (!logMessage.contains("tcp_client_connection_duration_ms\"")) {
      // This is expected - TCP connection not yet closed
      System.out.println("TCP timing fields correctly omitted (connection not closed yet)");
    } else {
      // If present, they should have actual values, not "-"
      assertThat(logMessage).doesNotContain("tcp_client_connection_duration_ms\":\"-\"");
      assertThat(logMessage).doesNotContain("tcp_server_connection_duration_ms\":\"-\"");
    }
  }

  @Test
  @Tag("integration")
  void testHttpRequestWithConnectionClose() throws Exception {
    // Add a stub that forces connection close
    wireMockServer.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get("/close-test")
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Connection", "close")
                    .withBody("Connection closed!")));

    // Start proxy with capturing logger
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .plusActivityTracker(createCapturingActivityLogger())
            .start();

    // Make HTTP request with Connection: close
    URL url = new URL("http://localhost:" + wireMockServer.port() + "/close-test");
    Proxy proxy =
        new Proxy(
            Proxy.Type.HTTP,
            new InetSocketAddress("localhost", proxyServer.getListenAddress().getPort()));

    HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(5000);

    int responseCode = connection.getResponseCode();
    assertThat(responseCode).isEqualTo(200);

    // Read response body
    try (BufferedReader reader =
        new BufferedReader(
            new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
      String response = reader.lines().collect(Collectors.joining("\n"));
      assertThat(response).contains("Connection closed!");
    }

    // Wait for connection to close
    Thread.sleep(500);

    // Verify that we captured at least one log message
    assertThat(capturedLogMessages).isNotEmpty();

    // Find the log message for this request
    String logMessage = null;
    for (String msg : capturedLogMessages) {
      if (msg.contains("close-test")) {
        logMessage = msg;
        break;
      }
    }

    if (logMessage != null) {
      System.out.println("Connection close log: " + logMessage);

      // Verify it's valid JSON
      assertThat(logMessage).startsWith("{");

      // HTTP fields should be present
      assertThat(logMessage).contains("\"method\":\"GET\"");
      assertThat(logMessage).contains("\"status\":\"200\"");

      // With Connection: close, we should have some TCP timing data
      // Verify that if fields are present, they have actual values
      if (logMessage.contains("tcp_connection_establishment_time_ms")) {
        assertThat(logMessage).doesNotContain("tcp_connection_establishment_time_ms\":\"-\"");
      }
    }
  }

  @Test
  @Tag("integration")
  void testMultipleRequestsOnKeepAliveConnection() throws Exception {
    // Start proxy with capturing logger
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .plusActivityTracker(createCapturingActivityLogger())
            .start();

    // Make multiple requests on the same connection (keep-alive)
    Proxy proxy =
        new Proxy(
            Proxy.Type.HTTP,
            new InetSocketAddress("localhost", proxyServer.getListenAddress().getPort()));

    for (int i = 0; i < 3; i++) {
      URL url = new URL("http://localhost:" + wireMockServer.port() + "/test");
      HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);

      int responseCode = connection.getResponseCode();
      assertThat(responseCode).isEqualTo(200);

      // Read response to ensure connection can be reused
      try (BufferedReader reader =
          new BufferedReader(
              new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
        reader.lines().collect(Collectors.joining());
      }
    }

    // Wait for logging
    Thread.sleep(100);

    // Verify we have multiple log messages
    assertThat(capturedLogMessages.size()).isGreaterThanOrEqualTo(3);

    System.out.println("Captured " + capturedLogMessages.size() + " log messages");

    // Check that all logs have proper format
    for (String logMessage : capturedLogMessages) {
      System.out.println("Log: " + logMessage);

      // Verify it's valid JSON
      assertThat(logMessage).startsWith("{");

      // HTTP fields should always be present
      assertThat(logMessage).contains("\"method\":\"GET\"");
      assertThat(logMessage).contains("\"status\":\"200\"");

      // TCP timing fields should either be omitted or have actual values
      // (not "-" placeholder)
      assertThat(logMessage).doesNotContain("tcp_server_connection_duration_ms\":\"-\"");
      assertThat(logMessage).doesNotContain("tcp_client_connection_duration_ms\":\"-\"");
      assertThat(logMessage).doesNotContain("tcp_connection_establishment_time_ms\":\"-\"");
      assertThat(logMessage).doesNotContain("ssl_handshake_time_ms\":\"-\"");
    }
  }
}
