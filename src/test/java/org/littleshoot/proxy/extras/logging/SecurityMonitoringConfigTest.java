package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecurityMonitoringConfigTest {

  @Test
  void testCreate() {
    LogFieldConfiguration config = SecurityMonitoringConfig.create();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateBasic() {
    LogFieldConfiguration config = SecurityMonitoringConfig.createBasic();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testCreateHasStandardFields() {
    LogFieldConfiguration config = SecurityMonitoringConfig.create();

    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("timestamp"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("client_ip"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("method"))).isTrue();
  }

  @Test
  void testCreateHasResponseTimeCategoryField() {
    LogFieldConfiguration config = SecurityMonitoringConfig.create();

    assertThat(
            config.getFields().stream().anyMatch(f -> f.getName().equals("response_time_category")))
        .isTrue();
  }

  @Test
  void testCreateBasicHasEssentialFields() {
    LogFieldConfiguration config = SecurityMonitoringConfig.createBasic();

    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("timestamp"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("client_ip"))).isTrue();
  }

  @Test
  void testCreateIsStrictStandardsCompliant() {
    LogFieldConfiguration config = SecurityMonitoringConfig.create();

    assertThat(config.isStrictStandardsCompliance()).isTrue();
  }

  @Test
  void testCreateBasicIsNotStrictStandardsCompliant() {
    LogFieldConfiguration config = SecurityMonitoringConfig.createBasic();

    assertThat(config.isStrictStandardsCompliance()).isFalse();
  }
}
