package org.littleshoot.proxy.extras.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating LogFieldConfiguration from various sources. Supports JSON configuration
 * files and CLI options.
 */
public class LogFieldConfigurationFactory {

  private static final Logger LOG = LoggerFactory.getLogger(LogFieldConfigurationFactory.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * Creates a LogFieldConfiguration from a JSON file.
   *
   * @param configFile the path to the JSON configuration file
   * @return the configured LogFieldConfiguration
   * @throws IOException if the file cannot be read or parsed
   */
  public static LogFieldConfiguration fromJsonFile(String configFile) throws IOException {
    File file = new File(configFile);
    if (!file.exists()) {
      throw new IOException("Configuration file not found: " + configFile);
    }

    LoggingConfiguration config = OBJECT_MAPPER.readValue(file, LoggingConfiguration.class);
    return buildConfiguration(config);
  }

  /**
   * Builds a LogFieldConfiguration from a LoggingConfiguration model.
   *
   * @param config the logging configuration model
   * @return the built LogFieldConfiguration
   */
  public static LogFieldConfiguration buildConfiguration(LoggingConfiguration config) {
    LogFieldConfiguration.Builder builder = LogFieldConfiguration.builder();

    // Add standard fields
    if (config.getStandardFields() != null) {
      for (String fieldName : config.getStandardFields()) {
        try {
          StandardField field = StandardField.valueOf(fieldName.toUpperCase());
          builder.addStandardField(field);
          LOG.debug("Added standard field: {}", field);
        } catch (IllegalArgumentException e) {
          LOG.warn("Unknown standard field: {}", fieldName);
        }
      }
    }

    // Add request prefix headers
    if (config.getRequestPrefixHeaders() != null) {
      for (LoggingConfiguration.PrefixHeaderConfig prefixConfig :
          config.getRequestPrefixHeaders()) {
        String prefix = prefixConfig.getPrefix();
        Function<String, String> fieldNameTransformer =
            getFieldNameTransformer(prefixConfig.getFieldNameTransformer());
        Function<String, String> valueTransformer =
            getValueTransformer(prefixConfig.getValueTransformer());

        if (valueTransformer != null) {
          builder.addRequestHeadersWithPrefix(prefix, fieldNameTransformer, valueTransformer);
        } else if (fieldNameTransformer != null) {
          builder.addRequestHeadersWithPrefix(prefix, fieldNameTransformer);
        } else {
          builder.addRequestHeadersWithPrefix(prefix);
        }
        LOG.debug("Added request prefix header matcher: {}", prefix);
      }
    }

    // Add response prefix headers
    if (config.getResponsePrefixHeaders() != null) {
      for (LoggingConfiguration.PrefixHeaderConfig prefixConfig :
          config.getResponsePrefixHeaders()) {
        String prefix = prefixConfig.getPrefix();
        Function<String, String> fieldNameTransformer =
            getFieldNameTransformer(prefixConfig.getFieldNameTransformer());
        Function<String, String> valueTransformer =
            getValueTransformer(prefixConfig.getValueTransformer());

        if (valueTransformer != null) {
          builder.addResponseHeadersWithPrefix(prefix, fieldNameTransformer, valueTransformer);
        } else if (fieldNameTransformer != null) {
          builder.addResponseHeadersWithPrefix(prefix, fieldNameTransformer);
        } else {
          builder.addResponseHeadersWithPrefix(prefix);
        }
        LOG.debug("Added response prefix header matcher: {}", prefix);
      }
    }

    // Add request regex headers
    if (config.getRequestRegexHeaders() != null) {
      for (LoggingConfiguration.RegexHeaderConfig regexConfig : config.getRequestRegexHeaders()) {
        String pattern = regexConfig.getPattern();
        Function<String, String> fieldNameTransformer =
            getFieldNameTransformer(regexConfig.getFieldNameTransformer());
        Function<String, String> valueTransformer =
            getValueTransformer(regexConfig.getValueTransformer());

        if (valueTransformer != null) {
          builder.addRequestHeadersMatching(pattern, fieldNameTransformer, valueTransformer);
        } else if (fieldNameTransformer != null) {
          builder.addRequestHeadersMatching(pattern, fieldNameTransformer);
        } else {
          builder.addRequestHeadersMatching(pattern);
        }
        LOG.debug("Added request regex header matcher: {}", pattern);
      }
    }

    // Add response regex headers
    if (config.getResponseRegexHeaders() != null) {
      for (LoggingConfiguration.RegexHeaderConfig regexConfig : config.getResponseRegexHeaders()) {
        String pattern = regexConfig.getPattern();
        Function<String, String> fieldNameTransformer =
            getFieldNameTransformer(regexConfig.getFieldNameTransformer());
        Function<String, String> valueTransformer =
            getValueTransformer(regexConfig.getValueTransformer());

        if (valueTransformer != null) {
          builder.addResponseHeadersMatching(pattern, fieldNameTransformer, valueTransformer);
        } else if (fieldNameTransformer != null) {
          builder.addResponseHeadersMatching(pattern, fieldNameTransformer);
        } else {
          builder.addResponseHeadersMatching(pattern);
        }
        LOG.debug("Added response regex header matcher: {}", pattern);
      }
    }

    // Add request exclude headers
    if (config.getRequestExcludeHeaders() != null) {
      for (LoggingConfiguration.ExcludeHeaderConfig excludeConfig :
          config.getRequestExcludeHeaders()) {
        String pattern = excludeConfig.getPattern();
        Function<String, String> fieldNameTransformer =
            getFieldNameTransformer(excludeConfig.getFieldNameTransformer());
        Function<String, String> valueTransformer =
            getValueTransformer(excludeConfig.getValueTransformer());

        if (fieldNameTransformer != null && valueTransformer != null) {
          builder.excludeRequestHeadersMatching(pattern, fieldNameTransformer, valueTransformer);
        } else {
          builder.excludeRequestHeadersMatching(pattern);
        }
        LOG.debug("Added request exclude header matcher: {}", pattern);
      }
    }

    // Add response exclude headers
    if (config.getResponseExcludeHeaders() != null) {
      for (LoggingConfiguration.ExcludeHeaderConfig excludeConfig :
          config.getResponseExcludeHeaders()) {
        String pattern = excludeConfig.getPattern();
        Function<String, String> fieldNameTransformer =
            getFieldNameTransformer(excludeConfig.getFieldNameTransformer());
        Function<String, String> valueTransformer =
            getValueTransformer(excludeConfig.getValueTransformer());

        if (fieldNameTransformer != null && valueTransformer != null) {
          builder.excludeResponseHeadersMatching(pattern, fieldNameTransformer, valueTransformer);
        } else {
          builder.excludeResponseHeadersMatching(pattern);
        }
        LOG.debug("Added response exclude header matcher: {}", pattern);
      }
    }

    // Add computed fields
    if (config.getComputedFields() != null) {
      for (String fieldName : config.getComputedFields()) {
        try {
          ComputedField field = ComputedField.valueOf(fieldName.toUpperCase());
          builder.addComputedField(field);
          LOG.debug("Added computed field: {}", field);
        } catch (IllegalArgumentException e) {
          LOG.warn("Unknown computed field: {}", fieldName);
        }
      }
    }

    // Add single headers
    if (config.getSingleHeaders() != null) {
      for (LoggingConfiguration.SingleHeaderConfig singleConfig : config.getSingleHeaders()) {
        String headerName = singleConfig.getHeaderName();
        String fieldName = singleConfig.getFieldName();

        if (singleConfig.isRequest()) {
          if (fieldName != null) {
            builder.addRequestHeader(headerName, fieldName);
          } else {
            builder.addRequestHeader(headerName);
          }
        } else {
          if (fieldName != null) {
            builder.addResponseHeader(headerName, fieldName);
          } else {
            builder.addResponseHeader(headerName);
          }
        }
        LOG.debug("Added single header: {}", headerName);
      }
    }

    return builder.build();
  }

  /**
   * Creates a basic LogFieldConfiguration with just standard fields. Useful as a fallback when no
   * configuration is provided.
   *
   * @return a basic LogFieldConfiguration
   */
  public static LogFieldConfiguration createBasicConfiguration() {
    return LogFieldConfiguration.builder()
        .addStandardField(StandardField.TIMESTAMP)
        .addStandardField(StandardField.CLIENT_IP)
        .addStandardField(StandardField.METHOD)
        .addStandardField(StandardField.URI)
        .addStandardField(StandardField.STATUS)
        .addStandardField(StandardField.BYTES)
        .build();
  }

  /**
   * Gets a field name transformer based on the transformer name.
   *
   * @param transformerName the name of the transformer
   * @return the transformer function, or null if not found
   */
  private static Function<String, String> getFieldNameTransformer(String transformerName) {
    if (transformerName == null) {
      return null;
    }

    switch (transformerName.toLowerCase()) {
      case "lower_underscore":
        return name -> name.toLowerCase().replaceAll("[^a-z0-9]", "_");
      case "remove_prefix":
        return name -> name.replaceAll("^X-", "").toLowerCase().replaceAll("[^a-z0-9]", "_");
      case "lower_hyphen":
        return name -> name.toLowerCase().replaceAll("[^a-z0-9]", "-");
      case "upper_underscore":
        return name -> name.toUpperCase().replaceAll("[^A-Z0-9]", "_");
      default:
        LOG.warn("Unknown field name transformer: {}", transformerName);
        return null;
    }
  }

  /**
   * Gets a value transformer based on the transformer name.
   *
   * @param transformerName the name of the transformer
   * @return the transformer function, or null if not found
   */
  private static Function<String, String> getValueTransformer(String transformerName) {
    if (transformerName == null) {
      return null;
    }

    switch (transformerName.toLowerCase()) {
      case "mask_sensitive":
        return value -> {
          if (value == null || value.length() <= 8) {
            return value;
          }
          return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
        };
      case "mask_full":
        return value -> "****";
      case "mask_email":
        return value -> {
          if (value == null || !value.contains("@")) {
            return value;
          }
          String[] parts = value.split("@");
          if (parts.length != 2) {
            return value;
          }
          String local = parts[0];
          String domain = parts[1];
          String maskedLocal = local.length() > 2 ? local.substring(0, 2) + "***" : "***";
          return maskedLocal + "@" + domain;
        };
      case "truncate_100":
        return value -> {
          if (value == null || value.length() <= 100) {
            return value;
          }
          return value.substring(0, 100) + "...";
        };
      default:
        LOG.warn("Unknown value transformer: {}", transformerName);
        return null;
    }
  }
}
