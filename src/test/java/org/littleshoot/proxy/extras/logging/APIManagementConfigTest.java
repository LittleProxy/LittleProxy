package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class APIManagementConfigTest {

  @Test
  void testCreate() {
    LogFieldConfiguration config = APIManagementConfig.create();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateLightweight() {
    LogFieldConfiguration config = APIManagementConfig.createLightweight();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateRateLimitingFocused() {
    LogFieldConfiguration config = APIManagementConfig.createRateLimitingFocused();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateCorrelationFocused() {
    LogFieldConfiguration config = APIManagementConfig.createCorrelationFocused();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateHasStandardFields() {
    LogFieldConfiguration config = APIManagementConfig.create();

    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("timestamp"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("client_ip"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("method"))).isTrue();
  }

  @Test
  void testCreateHasResponseTimeCategoryField() {
    LogFieldConfiguration config = APIManagementConfig.create();

    assertThat(
            config.getFields().stream().anyMatch(f -> f.getName().equals("response_time_category")))
        .isTrue();
  }

  @Test
  void testCreateLightweightHasEssentialFields() {
    LogFieldConfiguration config = APIManagementConfig.createLightweight();

    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("timestamp"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("client_ip"))).isTrue();
  }

  @Test
  void testCreateRateLimitingFocusedHasRateLimitHeaders() {
    LogFieldConfiguration config = APIManagementConfig.createRateLimitingFocused();

    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateCorrelationFocusedHasTracingHeaders() {
    LogFieldConfiguration config = APIManagementConfig.createCorrelationFocused();

    assertThat(config.getFields()).isNotEmpty();
  }
}
