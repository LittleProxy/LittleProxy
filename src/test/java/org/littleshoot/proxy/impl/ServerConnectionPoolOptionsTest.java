package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Verifies that implementation-specific pool options reach the options map passed to {@link
 * ServerConnectionPool#initialize}, both when declared as pool-scoped properties and when supplied
 * through the bootstrap. Pool-scoped property keys use relaxed binding: snake_case suffixes are
 * converted to camelCase option keys.
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
            ServerConnectionPool.OPTION_MAX_CONNECTIONS_PER_HOST,
            ServerConnectionPool.OPTION_MAX_CONNECTIONS,
            ServerConnectionPool.OPTION_IDLE_TIMEOUT)
        .containsEntry(ServerConnectionPool.OPTION_MAX_CONNECTIONS_PER_HOST, "3")
        .containsEntry(ServerConnectionPool.OPTION_MAX_CONNECTIONS, "7")
        .containsEntry(ServerConnectionPool.OPTION_IDLE_TIMEOUT, "PT60S");
  }

  @Test
  void shouldLowercaseSnakeCaseSegments() {
    Properties props = new Properties();
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".MAX_CONNECTIONS_PER_HOST",
        "3");
    props.setProperty(
        DefaultHttpProxyServer.SERVER_CONNECTION_POOL_OPTIONS_PREFIX
            + OptionsCapturingServerConnectionPool.NAME
            + ".Max_Retry_Delay",
        "PT5S");

    Map<String, Object> options =
        DefaultHttpProxyServerBootstrap.extractPoolOptions(
            props, OptionsCapturingServerConnectionPool.NAME);

    assertThat(options)
        .containsOnlyKeys("maxConnectionsPerHost", "maxRetryDelay")
        .containsEntry("maxConnectionsPerHost", "3")
        .containsEntry("maxRetryDelay", "PT5S");
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
              ServerConnectionPool.OPTION_MAX_CONNECTIONS_PER_HOST,
              ConcurrentMapServerConnectionPool.DEFAULT_MAX_CONNECTIONS_PER_HOST)
          .isUnmodifiable();
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

  @Test
  void shouldOmitIdleTimeoutOptionWhenDisabled() {
    DefaultHttpProxyServer server =
        (DefaultHttpProxyServer)
            new DefaultHttpProxyServerBootstrap()
                .withPort(0)
                .withSharedServerConnectionPool(true)
                .withServerConnectionPoolName(OptionsCapturingServerConnectionPool.NAME)
                .start();

    try {
      OptionsCapturingServerConnectionPool pool =
          (OptionsCapturingServerConnectionPool) server.getServerConnectionPool();
      // The SPI documents the idle timeout as absent when eviction is disabled, so the key must not
      // be present with a null value.
      assertThat(pool.getCapturedOptions())
          .doesNotContainKey(ServerConnectionPool.OPTION_IDLE_TIMEOUT)
          .containsKey(ServerConnectionPool.OPTION_MAX_CONNECTIONS_PER_HOST);
    } finally {
      server.stop();
    }
  }

  @Test
  void shouldRepresentNullIdleTimeoutOverrideAsAbsent() {
    DefaultHttpProxyServer server =
        (DefaultHttpProxyServer)
            new DefaultHttpProxyServerBootstrap()
                .withPort(0)
                .withSharedServerConnectionPool(true)
                .withServerConnectionPoolName(OptionsCapturingServerConnectionPool.NAME)
                .withPoolIdleTimeout(Duration.ofSeconds(30))
                .withServerConnectionPoolOption(ServerConnectionPool.OPTION_IDLE_TIMEOUT, null)
                .start();

    try {
      OptionsCapturingServerConnectionPool pool =
          (OptionsCapturingServerConnectionPool) server.getServerConnectionPool();
      assertThat(pool.getCapturedOptions())
          .doesNotContainKey(ServerConnectionPool.OPTION_IDLE_TIMEOUT);
    } finally {
      server.stop();
    }
  }

  @Test
  void shouldPreferProgrammaticOptionOverScopedProperty() {
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
        (DefaultHttpProxyServer)
            new DefaultHttpProxyServerBootstrap(props)
                .withPort(0)
                .withServerConnectionPoolOption("maxRetries", 99)
                .start();

    try {
      OptionsCapturingServerConnectionPool pool =
          (OptionsCapturingServerConnectionPool) server.getServerConnectionPool();
      assertThat(pool.getCapturedOptions()).containsEntry("maxRetries", 99);
    } finally {
      server.stop();
    }
  }

  @Test
  void shouldReportInvalidOptionOnEveryRequest() {
    DefaultHttpProxyServer server =
        (DefaultHttpProxyServer)
            new DefaultHttpProxyServerBootstrap()
                .withPort(0)
                .withSharedServerConnectionPool(true)
                .withServerConnectionPoolName(ServerConnectionPool.DEFAULT_NAME)
                .withServerConnectionPoolOption(
                    ServerConnectionPool.OPTION_MAX_CONNECTIONS_PER_HOST, "not-a-number")
                .start();

    try {
      assertThatThrownBy(server::getServerConnectionPool)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(ServerConnectionPool.OPTION_MAX_CONNECTIONS_PER_HOST);

      // The built-in pool validates before taking its one-shot guard, so the failed instance is
      // retried in place: the retry reports the same configuration error instead of being masked as
      // "already initialized".
      assertThatThrownBy(server::getServerConnectionPool)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(ServerConnectionPool.OPTION_MAX_CONNECTIONS_PER_HOST);
    } finally {
      server.abort();
    }
  }

  @Test
  void shouldRetrySamePoolInstanceWhenPoolIsRetryable() {
    DefaultHttpProxyServer server = startFailingPool(FailingServerConnectionPool.RETRYABLE);

    try {
      // The pool fails on its first initialize but not on later ones, so a successful retry proves
      // the server reused the same instance instead of resolving a fresh one (which would fail
      // again) and repeating the ServiceLoader classpath scan.
      assertThatThrownBy(server::getServerConnectionPool)
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(server.getServerConnectionPool()).isInstanceOf(FailingServerConnectionPool.class);
    } finally {
      server.abort();
    }
  }

  @Test
  void shouldResolveFreshPoolWhenPoolIsNotRetryable() {
    DefaultHttpProxyServer server = startFailingPool(FailingServerConnectionPool.NOT_RETRYABLE);

    try {
      assertThatThrownBy(server::getServerConnectionPool)
          .isInstanceOf(IllegalArgumentException.class);
      // The failed instance is not retryable, so it is discarded: the next request resolves a fresh
      // instance, which fails on its own first initialize.
      assertThatThrownBy(server::getServerConnectionPool)
          .isInstanceOf(IllegalArgumentException.class);
    } finally {
      server.abort();
    }
  }

  @Test
  void shouldResolveFreshPoolAfterInitializationError() {
    DefaultHttpProxyServer server = startFailingPool(FailingServerConnectionPool.ERROR);

    try {
      assertThatThrownBy(server::getServerConnectionPool).isInstanceOf(NoClassDefFoundError.class);
      // An Error is caught rather than escaping with the broken instance still cached, so the next
      // request resolves a fresh one.
      assertThatThrownBy(server::getServerConnectionPool).isInstanceOf(NoClassDefFoundError.class);
    } finally {
      server.abort();
    }
  }

  private static DefaultHttpProxyServer startFailingPool(String mode) {
    return (DefaultHttpProxyServer)
        new DefaultHttpProxyServerBootstrap()
            .withPort(0)
            .withSharedServerConnectionPool(true)
            .withServerConnectionPoolName(FailingServerConnectionPool.NAME)
            .withServerConnectionPoolOption(FailingServerConnectionPool.MODE_OPTION, mode)
            .start();
  }
}
