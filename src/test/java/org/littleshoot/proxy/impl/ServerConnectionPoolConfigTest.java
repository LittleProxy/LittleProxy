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
    assertThat(config.isPoolSharedMitmConnections()).isFalse();
    assertThat(config.isPoolPerRequestInMitm()).isFalse();
  }

  @Test
  void poolSharedMitmConnectionsDefaultsToFalse() {
    assertThat(new ServerConnectionPoolConfig().isPoolSharedMitmConnections()).isFalse();
  }

  @Test
  void poolSharedMitmConnectionsSetterAndGetter() {
    assertThat(config.setPoolSharedMitmConnections(true)).isSameAs(config);
    assertThat(config.isPoolSharedMitmConnections()).isTrue();
    config.setPoolSharedMitmConnections(false);
    assertThat(config.isPoolSharedMitmConnections()).isFalse();
  }

  @Test
  void poolPerRequestInMitmDefaultsToFalse() {
    assertThat(new ServerConnectionPoolConfig().isPoolPerRequestInMitm()).isFalse();
  }

  @Test
  void poolPerRequestInMitmSetterAndGetter() {
    assertThat(config.setPoolPerRequestInMitm(true)).isSameAs(config);
    assertThat(config.isPoolPerRequestInMitm()).isTrue();
    config.setPoolPerRequestInMitm(false);
    assertThat(config.isPoolPerRequestInMitm()).isFalse();
  }

  @Test
  void poolSharedMitmConnectionsRequiresEnabledPool() {
    // poolSharedMitmConnections can be set regardless of enabled state;
    // the actual behavior depends on the enabled flag at runtime.
    config.setEnabled(false).setPoolSharedMitmConnections(true);
    assertThat(config.isPoolSharedMitmConnections()).isTrue();
  }

  @Test
  void poolPerRequestInMitmRequiresPoolSharedMitmConnections() {
    // poolPerRequestInMitm requires poolSharedMitmConnections=true at runtime
    // but the config itself doesn't enforce this invariant.
    config.setPoolSharedMitmConnections(false).setPoolPerRequestInMitm(true);
    assertThat(config.isPoolPerRequestInMitm()).isTrue();
  }
}
