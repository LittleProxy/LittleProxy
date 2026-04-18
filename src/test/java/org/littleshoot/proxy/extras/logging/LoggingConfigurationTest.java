package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class LoggingConfigurationTest {

  @Test
  void testStandardFieldsGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    config.setStandardFields(Arrays.asList("TIMESTAMP", "CLIENT_IP", "METHOD"));

    assertThat(config.getStandardFields()).containsExactly("TIMESTAMP", "CLIENT_IP", "METHOD");
  }

  @Test
  void testResponseTimeThresholdsGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    config.setResponseTimeThresholds(Arrays.asList(100L, 500L, 2000L));

    assertThat(config.getResponseTimeThresholds()).containsExactly(100L, 500L, 2000L);
  }

  @Test
  void testRequestPrefixHeadersGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    LoggingConfiguration.PrefixHeaderConfig prefixConfig =
        new LoggingConfiguration.PrefixHeaderConfig();
    prefixConfig.setPrefix("X-Custom-");
    prefixConfig.setFieldNameTransformer("lower_underscore");
    config.setRequestPrefixHeaders(Collections.singletonList(prefixConfig));

    assertThat(config.getRequestPrefixHeaders()).hasSize(1);
    assertThat(config.getRequestPrefixHeaders().get(0).getPrefix()).isEqualTo("X-Custom-");
  }

  @Test
  void testResponsePrefixHeadersGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    LoggingConfiguration.PrefixHeaderConfig prefixConfig =
        new LoggingConfiguration.PrefixHeaderConfig();
    prefixConfig.setPrefix("X-RateLimit-");
    config.setResponsePrefixHeaders(Collections.singletonList(prefixConfig));

    assertThat(config.getResponsePrefixHeaders()).hasSize(1);
  }

  @Test
  void testRequestRegexHeadersGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    LoggingConfiguration.RegexHeaderConfig regexConfig =
        new LoggingConfiguration.RegexHeaderConfig();
    regexConfig.setPattern("X-.*-ID");
    config.setRequestRegexHeaders(Collections.singletonList(regexConfig));

    assertThat(config.getRequestRegexHeaders()).hasSize(1);
    assertThat(config.getRequestRegexHeaders().get(0).getPattern()).isEqualTo("X-.*-ID");
  }

  @Test
  void testResponseRegexHeadersGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    LoggingConfiguration.RegexHeaderConfig regexConfig =
        new LoggingConfiguration.RegexHeaderConfig();
    regexConfig.setPattern("X-Cache-.*");
    config.setResponseRegexHeaders(Collections.singletonList(regexConfig));

    assertThat(config.getResponseRegexHeaders()).hasSize(1);
  }

  @Test
  void testRequestExcludeHeadersGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    LoggingConfiguration.ExcludeHeaderConfig excludeConfig =
        new LoggingConfiguration.ExcludeHeaderConfig();
    excludeConfig.setPattern("Authorization|Cookie");
    config.setRequestExcludeHeaders(Collections.singletonList(excludeConfig));

    assertThat(config.getRequestExcludeHeaders()).hasSize(1);
  }

  @Test
  void testResponseExcludeHeadersGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    LoggingConfiguration.ExcludeHeaderConfig excludeConfig =
        new LoggingConfiguration.ExcludeHeaderConfig();
    excludeConfig.setPattern("Set-Cookie");
    config.setResponseExcludeHeaders(Collections.singletonList(excludeConfig));

    assertThat(config.getResponseExcludeHeaders()).hasSize(1);
  }

  @Test
  void testComputedFieldsGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    config.setComputedFields(Arrays.asList("GEOLOCATION_COUNTRY", "RESPONSE_TIME_CATEGORY"));

    assertThat(config.getComputedFields())
        .containsExactly("GEOLOCATION_COUNTRY", "RESPONSE_TIME_CATEGORY");
  }

  @Test
  void testSingleHeadersGetterSetter() {
    LoggingConfiguration config = new LoggingConfiguration();
    LoggingConfiguration.SingleHeaderConfig singleConfig =
        new LoggingConfiguration.SingleHeaderConfig();
    singleConfig.setHeaderName("X-Request-ID");
    singleConfig.setFieldName("request_id");
    singleConfig.setRequest(true);
    config.setSingleHeaders(Collections.singletonList(singleConfig));

    assertThat(config.getSingleHeaders()).hasSize(1);
    assertThat(config.getSingleHeaders().get(0).getHeaderName()).isEqualTo("X-Request-ID");
    assertThat(config.getSingleHeaders().get(0).getFieldName()).isEqualTo("request_id");
    assertThat(config.getSingleHeaders().get(0).isRequest()).isTrue();
  }

  @Test
  void testPrefixHeaderConfig() {
    LoggingConfiguration.PrefixHeaderConfig config = new LoggingConfiguration.PrefixHeaderConfig();
    config.setPrefix("X-Custom-");
    config.setFieldNameTransformer("lower_underscore");
    config.setValueTransformer("mask_sensitive");

    assertThat(config.getPrefix()).isEqualTo("X-Custom-");
    assertThat(config.getFieldNameTransformer()).isEqualTo("lower_underscore");
    assertThat(config.getValueTransformer()).isEqualTo("mask_sensitive");
  }

  @Test
  void testRegexHeaderConfig() {
    LoggingConfiguration.RegexHeaderConfig config = new LoggingConfiguration.RegexHeaderConfig();
    config.setPattern("X-.*-ID");
    config.setFieldNameTransformer("lower_hyphen");
    config.setValueTransformer("truncate_100");

    assertThat(config.getPattern()).isEqualTo("X-.*-ID");
    assertThat(config.getFieldNameTransformer()).isEqualTo("lower_hyphen");
    assertThat(config.getValueTransformer()).isEqualTo("truncate_100");
  }

  @Test
  void testExcludeHeaderConfig() {
    LoggingConfiguration.ExcludeHeaderConfig config =
        new LoggingConfiguration.ExcludeHeaderConfig();
    config.setPattern("Authorization");
    config.setFieldNameTransformer("upper_underscore");
    config.setValueTransformer("mask_full");

    assertThat(config.getPattern()).isEqualTo("Authorization");
    assertThat(config.getFieldNameTransformer()).isEqualTo("upper_underscore");
    assertThat(config.getValueTransformer()).isEqualTo("mask_full");
  }

  @Test
  void testSingleHeaderConfig() {
    LoggingConfiguration.SingleHeaderConfig config = new LoggingConfiguration.SingleHeaderConfig();
    config.setHeaderName("Content-Type");
    config.setFieldName("content_type");
    config.setValueTransformer("mask_email");
    config.setRequest(false);

    assertThat(config.getHeaderName()).isEqualTo("Content-Type");
    assertThat(config.getFieldName()).isEqualTo("content_type");
    assertThat(config.getValueTransformer()).isEqualTo("mask_email");
    assertThat(config.isRequest()).isFalse();
  }

  @Test
  void testNullValues() {
    LoggingConfiguration config = new LoggingConfiguration();

    assertThat(config.getStandardFields()).isNull();
    assertThat(config.getResponseTimeThresholds()).isNull();
    assertThat(config.getRequestPrefixHeaders()).isNull();
    assertThat(config.getComputedFields()).isNull();
  }
}
