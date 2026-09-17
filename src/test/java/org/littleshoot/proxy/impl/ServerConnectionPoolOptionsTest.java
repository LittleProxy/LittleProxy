package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Verifies that implementation-specific pool options reach the {@link ServerConnectionPoolContext}
 * options map, both when declared as pool-scoped properties and when supplied through the
 * bootstrap. Pool-scoped property keys use relaxed binding: snake_case suffixes are converted to
 * camelCase option keys.
 */
class ServerConnectionPoolOptionsTest {

  @Test
  void shouldCollectPoolScopedProperties() {
    Properties props = new Properties();
    props.setProperty("port", "0");
    props.setProperty(DefaultHttpProxyServer.USE_SHARED_SERVER_CONNECTION_POOL, "true");
    props.setProperty(DefaultHttpProxyServer.SERVER_CONNECTION_POOL_NAME, "DISCARDED_POOL_NAME");
    props.setProperty("server_connection_pool.OTHER_POOL.max_retries", "9");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".max_retries",
        "4");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".retry_delay",
        "PT5S");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".preCamel",
        "unchanged");

    Map<String, Object> options =
        DefaultHttpProxyServerBootstrap.extractPoolOptions(
            props, OptionsCapturingServerConnectionPool.NAME);

    assertThat(options)
        .containsOnlyKeys("maxRetries", "retryDelay", "preCamel")
        .containsEntry("maxRetries", "4")
        .containsEntry("retryDelay", "PT5S")
        .containsEntry("preCamel", "unchanged");
  }

  @Test
  void shouldMatchPoolScopedPrefixCaseInsensitively() {
    Properties props = new Properties();
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME.toLowerCase()
            + ".max_retries",
        "4");

    Map<String, Object> options =
        DefaultHttpProxyServerBootstrap.extractPoolOptions(
            props, OptionsCapturingServerConnectionPool.NAME);

    assertThat(options).containsOnlyKeys("maxRetries").containsEntry("maxRetries", "4");
  }

  @Test
  void shouldTranslateStandardSnakeCaseKeysToOptionKeys() {
    Properties props = new Properties();
    props.setProperty("port", "0");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".max_connections_per_host",
        "3");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".max_total_connections",
        "7");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".pool_idle_timeout",
        "PT60S");

    Map<String, Object> options =
        DefaultHttpProxyServerBootstrap.extractPoolOptions(
            props, OptionsCapturingServerConnectionPool.NAME);

    assertThat(options)
        .containsOnlyKeys(
            ServerConnectionPoolContext.OPTION_MAX_CONNECTIONS_PER_HOST,
            ServerConnectionPoolContext.OPTION_MAX_CONNECTIONS,
            ServerConnectionPoolContext.OPTION_IDLE_TIMEOUT)
        .containsEntry(ServerConnectionPoolContext.OPTION_MAX_CONNECTIONS_PER_HOST, "3")
        .containsEntry(ServerConnectionPoolContext.OPTION_MAX_CONNECTIONS, "7")
        .containsEntry(ServerConnectionPoolContext.OPTION_IDLE_TIMEOUT, "PT60S");
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
  void shouldDriveStandardOptionsFromScopedProperties() {
    Properties props = new Properties();
    props.setProperty("port", "0");
    props.setProperty(DefaultHttpProxyServer.USE_SHARED_SERVER_CONNECTION_POOL, "true");
    props.setProperty(DefaultHttpProxyServer.SERVER_CONNECTION_POOL_NAME, "concurrent_map");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + "concurrent_map.max_connections_per_host",
        "3");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + "concurrent_map.max_total_connections",
        "7");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + "concurrent_map.pool_idle_timeout",
        "PT60S");

    DefaultHttpProxyServer server =
        (DefaultHttpProxyServer) new DefaultHttpProxyServerBootstrap(props).start();

    try {
      ConcurrentMapServerConnectionPool pool =
          (ConcurrentMapServerConnectionPool) server.getServerConnectionPool();
      assertThat(pool.getMaxConnectionsPerHost()).isEqualTo(3);
      assertThat(pool.getMaxConnections()).isEqualTo(7);
      assertThat(pool.getIdleTimeout()).isEqualTo(Duration.ofSeconds(60));
    } finally {
      server.stop();
    }
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
            + ".max_retries",
        "4");

    DefaultHttpProxyServer server =
        (DefaultHttpProxyServer) new DefaultHttpProxyServerBootstrap(props).withPort(0).start();

    try {
      ServerConnectionPool pool = server.getServerConnectionPool();
      assertThat(pool).isInstanceOf(OptionsCapturingServerConnectionPool.class);
      Map<String, Object> options =
          ((OptionsCapturingServerConnectionPool) pool).getCapturedOptions();
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
      OptionsCapturingServerConnectionPool pool =
          (OptionsCapturingServerConnectionPool) server.getServerConnectionPool();
      assertThat(pool.getCapturedOptions()).containsEntry("batchSize", 42);
    } finally {
      server.stop();
    }
  }
}
