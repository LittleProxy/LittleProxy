package org.littleshoot.proxy;

import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for the Launcher class, specifically testing the start method. */
class LauncherTest {
  private Launcher launcher;
  private final int port = 0;

  @BeforeEach
  void setUp() {
    launcher = new Launcher();
  }

  @AfterEach
  void tearDown() {
    launcher.stop();
  }

  /**
   * Test that the start method handles the help option correctly. Should print help and exit
   * gracefully without starting the server.
   */
  @Test
  void testStartWithHelpOption() {
    // Given
    String[] args = {"--help"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles valid port option. */
  @Test
  void testStartWithValidPortOption() {

    // Given
    String[] args = {"--port", "" + port};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid port option gracefully. */
  @Test
  void testStartWithInvalidPortOption() {
    // Given
    String[] args = {"--port", "invalid"};

    // When/Then - should not throw exception and handle gracefully
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles MITM option. */
  @Test
  void testStartWithMitmOption() {
    // Given
    String[] args = {
      "--port",
      "" + port,
      "--mitm",
      "--ssl_clients_keystore_path",
      "target/testStartWithMitmOption_keystore.jks"
    };

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles DNSSEC option. */
  @Test
  void testStartWithDnssecOption() {
    // Given
    String[] args = {"--port", "" + port, "--dnssec", "true"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid DNSSEC option gracefully. */
  @Test
  void testStartWithInvalidDnssecOption() {
    // Given
    String[] args = {"--dnssec", "invalid"};

    // When/Then - should not throw exception and handle gracefully
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles config file option. */
  @Test
  void testStartWithConfigOption() {
    // Given
    String[] args = {"--port", "" + port, "--config", "src/test/resources/littleproxy.properties"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  @Test
  void testStartWithConfigOptionWithAnAbsentPropertiesFile() {
    // Given
    String[] args = {"--port", "" + port, "--config", "src/test/resources/notfound.properties"};

    // When/Then - should not throw exception
    assertThrows(IllegalArgumentException.class, () -> launcher.start(args));
  }

  @Test
  void testStartWithConfigOptionWithADirectoryFile() {
    // Given
    String[] args = {"--port", "" + port, "--config", "src/test/resources"};

    // When/Then - should not throw exception
    assertThrows(IllegalArgumentException.class, () -> launcher.start(args));
  }

  /** Test that the start method handles throttling options. */
  @Test
  void testStartWithThrottlingOptions() {
    // Given
    String[] args = {
      "--port",
      "" + port,
      "--throttle_read_bytes_per_second",
      "1000",
      "--throttle_write_bytes_per_second",
      "2000"
    };

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles activity log format option. */
  @Test
  void testStartWithActivityLogFormat() {
    // Given
    String[] args = {"--port", "" + port, "--activity_log_format", "CLF"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid activity log format gracefully. */
  @Test
  void testStartWithInvalidActivityLogFormat() {
    // Given
    String[] args = {"--activity_log_format", "INVALID"};

    // When/Then - should not throw exception and handle gracefully
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method works with no arguments (default configuration). */
  @Test
  void testStartWithNoArgs() {
    // Given
    String[] args = {"--port", "" + port};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles extra/unrecognized arguments by throwing exception. */
  @Test
  void testStartWithExtraArguments() {
    // Given
    String[] args = {"extra", "arguments"};

    // When/Then - should throw exception for unrecognized arguments
    assertThrows(IllegalArgumentException.class, () -> launcher.start(args));
  }

  /**
   * Test that the start method handles server mode (though it will hang, so we test in separate
   * thread).
   */
  @Test
  void testStartWithServerOption() throws InterruptedException {
    // Given
    String[] args = {"--server"};

    // When - run in separate thread to avoid hanging
    Thread testThread = new Thread(() -> launcher.start(args));
    testThread.start();
    waitForServerToStart();
    assertThat(launcher.isRunning()).isTrue();

    testThread.interrupt();
    testThread.join(1000);

    // Then - thread should terminate
    waitForServerToStop();
    assertThat(launcher.isRunning()).isFalse();
    assertSame(Thread.State.TERMINATED, testThread.getState());
  }

  /** Test that the start method handles NIC option. */
  @Test
  void testStartWithNicOption() {
    // Given
    String[] args = {"--port", "" + port, "--nic", "localhost"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles name option. */
  @Test
  void testStartWithNameOption() {
    // Given
    String[] args = {"--port", "" + port, "--name", "TestProxy"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles address option. */
  @Test
  void testStartWithAddressOption() {
    // Given
    String[] args = {"--port", "" + port, "--address", "127.0.0.1:" + port};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles proxy alias option. */
  @Test
  void testStartWithProxyAliasOption() {
    // Given
    String[] args = {"--port", "" + port, "--proxy_alias", "test-alias"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles allow local only option. */
  @Test
  void testStartWithAllowLocalOnlyOption() {
    // Given
    String[] args = {"--port", "" + port, "--allow_local_only", "true"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid allow local only option gracefully. */
  @Test
  void testStartWithInvalidAllowLocalOnlyOption() {
    // Given
    String[] args = {"--port", "" + port, "--allow_local_only", "invalid"};

    // When/Then - should not throw exception and handle gracefully
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles authenticate SSL clients option. */
  @Test
  void testStartWithAuthenticateSslClientsOption() {
    // Given
    String[] args = {
      "--port",
      "" + port,
      "--authenticate_ssl_clients",
      "true",
      "--ssl_clients_keystore_path",
      "target/testStartWithAuthenticateSslClientsOption_keystore.jks"
    };

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles SSL clients trust all servers option. */
  @Test
  void testStartWithSslClientsTrustAllServersOption() {
    // Given
    String[] args = {
      "--port",
      "" + port,
      "--authenticate_ssl_clients",
      "true",
      "--ssl_clients_trust_all_servers",
      "true",
      "--ssl_clients_keystore_path",
      "target/testStartWithSslClientsTrustAllServersOption_keystore.jks"
    };

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles SSL clients send certs option. */
  @Test
  void testStartWithSslClientsSendCertsOption() {
    // Given
    String[] args = {
      "--port",
      "" + port,
      "--authenticate_ssl_clients",
      "true",
      "--ssl_clients_send_certs",
      "true",
      "--ssl_clients_keystore_path",
      "target/testStartWithSslClientsSendCertsOption_keystore.jks"
    };

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles transparent option. */
  @Test
  void testStartWithTransparentOption() {
    // Given
    String[] args = {"--port", "" + port, "--transparent", "true"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid transparent option gracefully. */
  @Test
  void testStartWithInvalidTransparentOption() {
    // Given
    String[] args = {"--port", "" + port, "--transparent", "invalid"};

    // When/Then - should not throw exception and handle gracefully
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles allow requests to origin server option. */
  @Test
  void testStartWithAllowRequestsToOriginServerOption() {
    // Given
    String[] args = {"--port", "" + port, "--allow_requests_to_origin_server", "true"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /**
   * Test that the start method handles invalid allow requests to origin server option gracefully.
   */
  @Test
  void testStartWithInvalidAllowRequestsToOriginServerOption() {
    // Given
    String[] args = {"--port", "" + port, "--allow_requests_to_origin_server", "invalid"};

    // When/Then - should not throw exception and handle gracefully
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles allow proxy protocol option. */
  @Test
  void testStartWithAllowProxyProtocolOption() {
    // Given
    String[] args = {"--port", "" + port, "--allow_proxy_protocol", "true"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid allow proxy protocol option gracefully. */
  @Test
  void testStartWithInvalidAllowProxyProtocolOption() {
    // Given
    String[] args = {"--port", "" + port, "--allow_proxy_protocol", "invalid"};

    // When/Then - should not throw exception and handle gracefully
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles send proxy protocol option. */
  @Test
  void testStartWithSendProxyProtocolOption() {
    // Given
    String[] args = {"--port", "" + port, "--send_proxy_protocol", "true"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid send proxy protocol option gracefully. */
  @Test
  void testStartWithInvalidSendProxyProtocolOption() {
    // Given
    String[] args = {"--port", "" + port, "--send_proxy_protocol", "invalid"};

    // When/Then - should not throw exception and handle gracefully
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles client to proxy worker threads option. */
  @Test
  void testStartWithClientToProxyWorkerThreadsOption() {
    // Given
    String[] args = {"--port", "" + port, "--client_to_proxy_worker_threads", "4"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid client to proxy worker threads option. */
  @Test
  void testStartWithInvalidClientToProxyWorkerThreadsOption() {
    // Given
    String[] args = {"--client_to_proxy_worker_threads", "invalid"};

    // When/Then - should throw exception for invalid numeric value
    assertThrows(NumberFormatException.class, () -> launcher.start(args));
  }

  /** Test that the start method handles proxy to server worker threads option. */
  @Test
  void testStartWithProxyToServerWorkerThreadsOption() {
    // Given
    String[] args = {"--port", "" + port, "--proxy_to_server_worker_threads", "4"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid proxy to server worker threads option. */
  @Test
  void testStartWithInvalidProxyToServerWorkerThreadsOption() {
    // Given
    String[] args = {"--proxy_to_server_worker_threads", "invalid"};

    // When/Then - should throw exception for invalid numeric value
    assertThrows(NumberFormatException.class, () -> launcher.start(args));
  }

  /** Test that the start method handles acceptor threads option. */
  @Test
  void testStartWithAcceptorThreadsOption() {
    // Given
    String[] args = {"--port", "" + port, "--acceptor_threads", "2"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid acceptor threads option. */
  @Test
  void testStartWithInvalidAcceptorThreadsOption() {
    // Given
    String[] args = {"--acceptor_threads", "invalid"};

    // When/Then - should throw exception for invalid numeric value
    assertThrows(NumberFormatException.class, () -> launcher.start(args));
  }

  /** Test that the start method handles log config option. */
  @Test
  void testStartWithLogConfigOption() {
    // Given
    String[] args = {"--port", "" + port, "--log_config", "src/test/resources/log4j.xml"};

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles invalid log config option. */
  @Test
  void testStartWithInvalidLogConfigOption() {
    // Given
    String[] args = {"--port", "" + port, "--log_config", "nonexistent.xml"};

    // When/Then - should not throw exception and handle gracefully
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles multiple options combination. */
  @Test
  void testStartWithMultipleOptions() {
    // Given
    String[] args = {
      "--port",
      "" + port,
      "--mitm",
      "--dnssec",
      "true",
      "--name",
      "TestProxy",
      "--allow_local_only",
      "true",
      "--ssl_clients_keystore_path",
      "target/testStartWithMultipleOptions_keystore.jks"
    };

    // When/Then - should not throw exception
    assertDoesNotThrow(() -> launcher.start(args));
  }

  /** Test that the start method handles missing required values for options. */
  @Test
  void testStartWithMissingRequiredValues() {
    // Given - port option without value
    String[] args = {"--port"};

    // When/Then - should throw exception for missing required value
    assertThrows(IllegalArgumentException.class, () -> launcher.start(args));
  }

  /** Test that the start method handles invalid port values. */
  @Test
  void testStartWithInvalidPortValues() {
    // Given - port with negative value
    String[] args = {"--port", "-1"};

    // When/Then - should throw exception for invalid port value
    assertThrows(IllegalArgumentException.class, () -> launcher.start(args));
  }

  /** Test that the start method handles port value that is too large. */
  @Test
  void testStartWithPortValueTooLarge() {
    // Given - port with very large value
    String[] args = {"--port", "999999"};

    // When/Then - should throw exception for port out of range
    assertThrows(IllegalArgumentException.class, () -> launcher.start(args));
  }

  private void waitForServerToStart() throws InterruptedException {
    waitForServer(true);
  }

  private void waitForServerToStop() throws InterruptedException {
    waitForServer(false);
  }

  private void waitForServer(boolean alive) throws InterruptedException {
    long timeout = SECONDS.toNanos(10);
    for (long start = nanoTime(); launcher.isRunning() != alive && nanoTime() - start < timeout; ) {
      Thread.sleep(10);
    }
  }
}
