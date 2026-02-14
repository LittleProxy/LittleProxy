package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFieldConfigurationFactoryTest {

  @TempDir File tempDir;

  @Test
  void testCreateBasicConfiguration() {
    LogFieldConfiguration config = LogFieldConfigurationFactory.createBasicConfiguration();

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("timestamp"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("client_ip"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("method"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("uri"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("status"))).isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("bytes"))).isTrue();
  }

  @Test
  void testFromJsonFileNotFound() {
    assertThatThrownBy(() -> LogFieldConfigurationFactory.fromJsonFile("/nonexistent/path.json"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Configuration file not found");
  }

  @Test
  void testFromJsonFileWithStandardFields() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{\"standardFields\": [\"TIMESTAMP\", \"CLIENT_IP\", \"METHOD\"]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields()).hasSize(3);
  }

  @Test
  void testFromJsonFileWithResponseTimeThresholds() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write(
          "{\"responseTimeThresholds\": [50, 200, 1000], \"computedFields\": [\"RESPONSE_TIME_CATEGORY\"]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getResponseTimeThresholds()).containsExactly(50L, 200L, 1000L);
    assertThat(
            config.getFields().stream().anyMatch(f -> f.getName().equals("response_time_category")))
        .isTrue();
  }

  @Test
  void testFromJsonFileWithComputedFields() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{\"computedFields\": [\"GEOLOCATION_COUNTRY\", \"AUTHENTICATION_TYPE\"]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("geolocation_country")))
        .isTrue();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("authentication_type")))
        .isTrue();
  }

  @Test
  void testFromJsonFileWithRequestPrefixHeaders() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{\"requestPrefixHeaders\": [{\"prefix\": \"X-Request-\"}]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testFromJsonFileWithResponsePrefixHeaders() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{\"responsePrefixHeaders\": [{\"prefix\": \"X-Cache-\"}]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testFromJsonFileWithRequestRegexHeaders() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{\"requestRegexHeaders\": [{\"pattern\": \"X-.*-ID\"}]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testFromJsonFileWithResponseRegexHeaders() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{\"responseRegexHeaders\": [{\"pattern\": \"X-RateLimit-.*\"}]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testFromJsonFileWithSingleHeaders() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write(
          "{\"singleHeaders\": ["
              + "{\"headerName\": \"X-Request-ID\", \"fieldName\": \"request_id\", \"request\": true},"
              + "{\"headerName\": \"X-Cache-Status\", \"fieldName\": \"cache_status\", \"request\": false}"
              + "]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields()).hasSize(2);
  }

  @Test
  void testFromJsonFileWithExcludeHeaders() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write(
          "{\"requestExcludeHeaders\": [{\"pattern\": \"Authorization\"}],"
              + "\"responseExcludeHeaders\": [{\"pattern\": \"Set-Cookie\"}]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
  }

  @Test
  void testFromJsonFileWithInvalidStandardField() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{\"standardFields\": [\"INVALID_FIELD\", \"TIMESTAMP\"]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("timestamp"))).isTrue();
  }

  @Test
  void testFromJsonFileWithInvalidComputedField() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{\"computedFields\": [\"INVALID_COMPUTED\", \"GEOLOCATION_COUNTRY\"]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields().stream().anyMatch(f -> f.getName().equals("geolocation_country")))
        .isTrue();
  }

  @Test
  void testFromJsonFileWithFieldNameTransformer() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write(
          "{\"requestPrefixHeaders\": [{\"prefix\": \"X-\", \"fieldNameTransformer\": \"lower_underscore\"}]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
  }

  @Test
  void testFromJsonFileWithValueTransformer() throws IOException {
    File configFile = tempDir.toPath().resolve("config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write(
          "{\"requestPrefixHeaders\": [{\"prefix\": \"Authorization\", \"valueTransformer\": \"mask_sensitive\"}]}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
  }

  @Test
  void testBuildConfigurationWithNullStandardFields() {
    LoggingConfiguration config = new LoggingConfiguration();
    config.setStandardFields(null);

    LogFieldConfiguration result = LogFieldConfigurationFactory.buildConfiguration(config);

    assertThat(result).isNotNull();
    assertThat(result.getFields()).isEmpty();
  }

  @Test
  void testBuildConfigurationWithEmptyStandardFields() {
    LoggingConfiguration config = new LoggingConfiguration();
    config.setStandardFields(Collections.emptyList());

    LogFieldConfiguration result = LogFieldConfigurationFactory.buildConfiguration(config);

    assertThat(result).isNotNull();
    assertThat(result.getFields()).isEmpty();
  }

  @Test
  void testBuildConfigurationWithNullResponseTimeThresholds() {
    LoggingConfiguration config = new LoggingConfiguration();
    config.setResponseTimeThresholds(null);

    LogFieldConfiguration result = LogFieldConfigurationFactory.buildConfiguration(config);

    assertThat(result).isNotNull();
    assertThat(result.getResponseTimeThresholds()).containsExactly(100L, 500L, 2000L);
  }

  @Test
  void testBuildConfigurationWithEmptyResponseTimeThresholds() {
    LoggingConfiguration config = new LoggingConfiguration();
    config.setResponseTimeThresholds(Collections.emptyList());

    LogFieldConfiguration result = LogFieldConfigurationFactory.buildConfiguration(config);

    assertThat(result).isNotNull();
    assertThat(result.getResponseTimeThresholds()).containsExactly(100L, 500L, 2000L);
  }

  @Test
  void testFromJsonFileWithComplexConfig() throws IOException {
    File configFile = tempDir.toPath().resolve("complex-config.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write(
          "{"
              + "\"responseTimeThresholds\": [50, 200, 1000],"
              + "\"standardFields\": [\"TIMESTAMP\", \"CLIENT_IP\", \"METHOD\", \"URI\", \"STATUS\"],"
              + "\"computedFields\": [\"GEOLOCATION_COUNTRY\", \"RESPONSE_TIME_CATEGORY\"],"
              + "\"requestPrefixHeaders\": [{\"prefix\": \"X-Request-\", \"fieldNameTransformer\": \"lower_underscore\"}],"
              + "\"responseRegexHeaders\": [{\"pattern\": \"X-RateLimit-.*\"}],"
              + "\"singleHeaders\": [{\"headerName\": \"Authorization\", \"fieldName\": \"auth\", \"request\": true}]"
              + "}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getResponseTimeThresholds()).containsExactly(50L, 200L, 1000L);
    assertThat(config.getFields()).isNotEmpty();
  }

  @Test
  void testFromJsonFileWithInvalidJson() throws IOException {
    File configFile = tempDir.toPath().resolve("invalid.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{ invalid json }");
    }

    assertThatThrownBy(() -> LogFieldConfigurationFactory.fromJsonFile(configFile.getPath()))
        .isInstanceOf(IOException.class);
  }

  @Test
  void testFromJsonFileEmpty() throws IOException {
    File configFile = tempDir.toPath().resolve("empty.json").toFile();
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write("{}");
    }

    LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile(configFile.getPath());

    assertThat(config).isNotNull();
    assertThat(config.getFields()).isEmpty();
  }
}
