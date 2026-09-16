package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that implementation-specific pool options reach the {@link ServerConnectionPoolContext}
 * options map, both when declared as pool-scoped properties and when supplied through the
 * bootstrap.
 */
class ServerConnectionPoolOptionsTest {

  @AfterEach
  void clearCapture() {
    OptionsCapturingServerConnectionPool.capturedOptions.set(null);
  }

  @Test
  void shouldCollectPoolScopedProperties() {
    Properties props = new Properties();
    props.setProperty("port", "0");
    props.setProperty(DefaultHttpProxyServer.USE_SHARED_SERVER_CONNECTION_POOL, "true");
    props.setProperty(DefaultHttpProxyServer.SERVER_CONNECTION_POOL_NAME, "DISCARDED_POOL_NAME");
    props.setProperty("server_connection_pool.OTHER_POOL.maxRetries", "9");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".maxRetries",
        "4");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".retryDelay",
        "PT5S");

    Map<String, Object> options =
        DefaultHttpProxyServerBootstrap.extractPoolOptions(
            props, OptionsCapturingServerConnectionPool.NAME);

    assertThat(options)
        .containsOnlyKeys("maxRetries", "retryDelay")
        .containsEntry("maxRetries", "4")
        .containsEntry("retryDelay", "PT5S");
  }

  @Test
  void shouldReturnEmptyWhenNoPoolScopedProperties() {
    Properties props = new Properties();
    props.setProperty("port", "0");

    assertThat(
            DefaultHttpProxyServerBootstrap.extractPoolOptions(
                props, OptionsCapturingServerConnectionPool.NAME))
        .isEmpty();
  }

  @Test
  void shouldDeliverPoolScopedPropertiesToInitializedPool() {
    Properties props = new Properties();
    props.setProperty("port", "0");
    props.setProperty(DefaultHttpProxyServer.USE_SHARED_SERVER_CONNECTION_POOL, "true");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_NAME,
        OptionsCapturingServerConnectionPool.NAME);
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".maxRetries",
        "4");

    DefaultHttpProxyServer server =
        (DefaultHttpProxyServer) new DefaultHttpProxyServerBootstrap(props).withPort(0).start();

    try {
      ServerConnectionPool pool = server.getServerConnectionPool();
      assertThat(pool).isInstanceOf(OptionsCapturingServerConnectionPool.class);
      Map<String, Object> options = OptionsCapturingServerConnectionPool.capturedOptions.get();
      assertThat(options)
          .containsEntry("maxRetries", "4")
          .containsEntry(
              ServerConnectionPoolContext.OPTION_MAX_CONNECTIONS_PER_HOST,
              ConcurrentMapServerConnectionPool.DEFAULT_MAX_CONNECTIONS_PER_HOST);
    } finally {
      server.stop();
    }
  }

  @Test
  void shouldDeliverProgrammaticOptionToInitializedPool() {
    DefaultHttpProxyServer server =
        (DefaultHttpProxyServer)
            new DefaultHttpProxyServerBootstrap()
                .withPort(0)
                .withSharedServerConnectionPool(true)
                .withServerConnectionPoolName(OptionsCapturingServerConnectionPool.NAME)
                .withServerConnectionPoolOption("batchSize", 42)
                .start();

    try {
      server.getServerConnectionPool();
      Map<String, Object> options = OptionsCapturingServerConnectionPool.capturedOptions.get();
      assertThat(options).containsEntry("batchSize", 42);
    } finally {
      server.stop();
    }
  }
}
