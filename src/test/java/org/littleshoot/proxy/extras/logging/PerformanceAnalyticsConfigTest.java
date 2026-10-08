package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PerformanceAnalyticsConfigTest {

  @Test
  void testCreate() {
    LogFieldConfiguration config = PerformanceAnalyticsConfig.create();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateCdnFocused() {
    LogFieldConfiguration config = PerformanceAnalyticsConfig.createCdnFocused();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateCacheFocused() {
    LogFieldConfiguration config = PerformanceAnalyticsConfig.createCacheFocused();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateHasStandardFields() {
    LogFieldConfiguration config = PerformanceAnalyticsConfig.create();

    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("timestamp"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("client_ip"))).isTrue();
    assertThat(
            config.getFields().stream()
                .anyMatch(f -> f.getName().equals("http_request_processing_time_ms")))
        .isTrue();
  }

  @Test
  void testCreateHasResponseTimeCategoryField() {
    LogFieldConfiguration config = PerformanceAnalyticsConfig.create();

    assertThat(
            config.getFields().stream().anyMatch(f -> f.getName().equals("response_time_category")))
        .isTrue();
  }

  @Test
  void testCreateCdnFocusedHasEssentialFields() {
    LogFieldConfiguration config = PerformanceAnalyticsConfig.createCdnFocused();

    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("timestamp"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("client_ip"))).isTrue();
  }

  @Test
  void testCreateCacheFocusedHasCacheFields() {
    LogFieldConfiguration config = PerformanceAnalyticsConfig.createCacheFocused();

    assertThat(config.getFields()).isNotEmpty();
  }
}
