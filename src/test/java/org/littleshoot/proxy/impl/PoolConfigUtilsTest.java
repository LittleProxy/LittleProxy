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
    assertThat(PoolConfigUtils.intValue("k", 3.0)).isEqualTo(3);
  }

  @Test
  void shouldRejectNonIntegralNumber() {
    assertThatThrownBy(() -> PoolConfigUtils.intValue("k", 3.5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("k");
  }

  @Test
  void shouldRejectNumberOutsideIntRange() {
    assertThatThrownBy(() -> PoolConfigUtils.intValue("k", 2_147_483_648L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("k");
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
    assertThat(PoolConfigUtils.booleanValue("k", "TRUE")).isTrue();
    assertThat(PoolConfigUtils.booleanValue("k", " false ")).isFalse();
  }

  @Test
  void shouldRejectInvalidBooleanString() {
    assertThatThrownBy(() -> PoolConfigUtils.booleanValue("k", "flase"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("k");
  }

  @Test
  void shouldConvertDurationFromIsoAndSeconds() {
    assertThat(PoolConfigUtils.durationValue("k", Duration.ofSeconds(90)))
        .isEqualTo(Duration.ofSeconds(90));
    assertThat(PoolConfigUtils.durationValue("k", "PT90S")).isEqualTo(Duration.ofSeconds(90));
    assertThat(PoolConfigUtils.durationValue("k", "90")).isEqualTo(Duration.ofSeconds(90));
    assertThat(PoolConfigUtils.durationValue("k", 90)).isEqualTo(Duration.ofSeconds(90));
    assertThat(PoolConfigUtils.durationValue("k", 90L)).isEqualTo(Duration.ofSeconds(90));
  }

  @Test
  void shouldRejectInvalidDuration() {
    assertThatThrownBy(() -> PoolConfigUtils.durationValue("k", "abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("k");
  }
}
