package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PoolConfigUtilsTest {

  @Test
  void shouldConvertIntegerAndMessage() {
    assertThat(PoolConfigUtils.intValue("k", 42)).isEqualTo(42);
    assertThat(PoolConfigUtils.intValue("k", "42")).isEqualTo(42);
    assertThat(PoolConfigUtils.intValue("k", 42L)).isEqualTo(42);
  }

  @Test
  void shouldRejectNonNumericString() {
    assertThatThrownBy(() -> PoolConfigUtils.intValue("maxConnections", "abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxConnections");
  }

  @Test
  void shouldRejectUnsupportedType() {
    assertThatThrownBy(() -> PoolConfigUtils.intValue("k", Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not an integer");
  }

  @Test
  void shouldConvertBoolean() {
    assertThat(PoolConfigUtils.booleanValue("k", true)).isTrue();
    assertThat(PoolConfigUtils.booleanValue("k", "true")).isTrue();
  }

  @Test
  void shouldConvertDurationFromIsoAndSeconds() {
    assertThat(PoolConfigUtils.durationValue("k", Duration.ofSeconds(90)))
        .isEqualTo(Duration.ofSeconds(90));
    assertThat(PoolConfigUtils.durationValue("k", "PT90S")).isEqualTo(Duration.ofSeconds(90));
    assertThat(PoolConfigUtils.durationValue("k", "90")).isEqualTo(Duration.ofSeconds(90));
  }

  @Test
  void shouldRejectInvalidDuration() {
    assertThatThrownBy(() -> PoolConfigUtils.durationValue("k", "abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("k");
  }
}
