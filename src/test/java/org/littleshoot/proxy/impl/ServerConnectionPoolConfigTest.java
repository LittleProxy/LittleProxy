package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ServerConnectionPoolType;

class ServerConnectionPoolConfigTest {

  private final ServerConnectionPoolConfig config = new ServerConnectionPoolConfig();

  @Test
  void setPoolTypeRejectsNull() {
    assertThatThrownBy(() -> config.setPoolType(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("poolType");
  }

  @Test
  void setPoolTypeAcceptsValidValue() {
    config.setPoolType(ServerConnectionPoolType.COMMONS_POOL2);
    assertThat(config.getPoolType()).isEqualTo(ServerConnectionPoolType.COMMONS_POOL2);
  }

  @Test
  void setMaxConnectionsPerHostRejectsZero() {
    assertThatThrownBy(() -> config.setMaxConnectionsPerHost(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setMaxConnectionsPerHostRejectsNegative() {
    assertThatThrownBy(() -> config.setMaxConnectionsPerHost(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setMaxConnectionsPerHostAcceptsPositive() {
    config.setMaxConnectionsPerHost(5);
    assertThat(config.getMaxConnectionsPerHost()).isEqualTo(5);
  }

  @Test
  void setMaxConnectionsRejectsZero() {
    assertThatThrownBy(() -> config.setMaxConnections(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setMaxConnectionsRejectsNegative() {
    assertThatThrownBy(() -> config.setMaxConnections(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setMaxConnectionsAcceptsPositive() {
    config.setMaxConnections(100);
    assertThat(config.getMaxConnections()).isEqualTo(100);
  }

  @Test
  void idleTimeoutIsNullable() {
    assertThat(config.getIdleTimeout()).isNull();
    config.setIdleTimeout(Duration.ofSeconds(30));
    assertThat(config.getIdleTimeout()).isEqualTo(Duration.ofSeconds(30));
    config.setIdleTimeout(null);
    assertThat(config.getIdleTimeout()).isNull();
  }

  @Test
  void defaults() {
    assertThat(config.isEnabled()).isFalse();
    assertThat(config.getPoolType()).isEqualTo(ServerConnectionPoolType.CONCURRENT_MAP);
    assertThat(config.getMaxConnectionsPerHost()).isEqualTo(10);
    assertThat(config.getMaxConnections()).isEqualTo(200);
    assertThat(config.getIdleTimeout()).isNull();
  }
}
