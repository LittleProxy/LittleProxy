package org.littleshoot.proxy.extras.logging;

import java.util.*;

/**
 * Configuration class that defines which fields should be logged by ActivityLogger.
 * This class uses the builder pattern to provide a fluent API for configuring
 * log fields with fine-grained control.
 */
public class LogFieldConfiguration {
    
    private final Set<LogField> fields;
    private final boolean strictStandardsCompliance;
    private final Map<String, String> customFieldMappings;
    
    private LogFieldConfiguration(Builder builder) {
        this.fields = Collections.unmodifiableSet(new HashSet<>(builder.fields));
        this.strictStandardsCompliance = builder.strictStandardsCompliance;
        this.customFieldMappings = Collections.unmodifiableMap(new HashMap<>(builder.customFieldMappings));
    }
    
    /**
     * Gets all configured log fields.
     * @return an unmodifiable set of log fields
     */
    public Set<LogField> getFields() {
        return fields;
    }
    
    /**
     * Checks if strict standards compliance is enabled.
     * @return true if strict compliance is enabled
     */
    public boolean isStrictStandardsCompliance() {
        return strictStandardsCompliance;
    }
    
    /**
     * Gets custom field name mappings.
     * @return an unmodifiable map of field name mappings
     */
    public Map<String, String> getCustomFieldMappings() {
        return customFieldMappings;
    }
    
    /**
     * Checks if a specific field is configured.
     * @param field the field to check
     * @return true if the field is configured
     */
    public boolean hasField(LogField field) {
        return fields.contains(field);
    }
    
    /**
     * Gets a field by name.
     * @param name the field name
     * @return the field if found, null otherwise
     */
    public LogField getFieldByName(String name) {
        return fields.stream()
                .filter(field -> field.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Creates a new builder for LogFieldConfiguration.
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a default configuration with standard fields.
     * @return a default configuration
     */
    public static LogFieldConfiguration defaultConfig() {
        return builder()
                .addStandardFields()
                .strictStandardsCompliance(false)
                .build();
    }
    
    /**
     * Builder class for LogFieldConfiguration.
     */
    public static class Builder {
        private final Set<LogField> fields = new HashSet<>();
        private boolean strictStandardsCompliance = false;
        private final Map<String, String> customFieldMappings = new HashMap<>();
        
        /**
         * Adds all standard fields to the configuration.
         * @return this builder for chaining
         */
        public Builder addStandardFields() {
            Collections.addAll(fields, StandardField.values());
            return this;
        }
        
        /**
         * Adds a specific standard field.
         * @param field the standard field to add
         * @return this builder for chaining
         */
        public Builder addStandardField(StandardField field) {
            fields.add(field);
            return this;
        }
        
        /**
         * Adds a request header field.
         * @param headerName the header name to log
         * @return this builder for chaining
         */
        public Builder addRequestHeader(String headerName) {
            fields.add(new RequestHeaderField(headerName));
            return this;
        }
        
        /**
         * Adds a request header field with custom field name.
         * @param headerName the header name to log
         * @param fieldName the field name to use in logs
         * @return this builder for chaining
         */
        public Builder addRequestHeader(String headerName, String fieldName) {
            fields.add(new RequestHeaderField(headerName, fieldName));
            return this;
        }
        
        /**
         * Adds a response header field.
         * @param headerName the header name to log
         * @return this builder for chaining
         */
        public Builder addResponseHeader(String headerName) {
            fields.add(new ResponseHeaderField(headerName));
            return this;
        }
        
        /**
         * Adds a response header field with custom field name.
         * @param headerName the header name to log
         * @param fieldName the field name to use in logs
         * @return this builder for chaining
         */
        public Builder addResponseHeader(String headerName, String fieldName) {
            fields.add(new ResponseHeaderField(headerName, fieldName));
            return this;
        }

        /**
         * Adds all request headers matching a prefix.
         * @param prefix the prefix to match (e.g., "X-Custom-")
         * @return this builder for chaining
         */
        public Builder addRequestHeadersWithPrefix(String prefix) {
            fields.add(new PrefixRequestHeaderField(prefix));
            return this;
        }

        /**
         * Adds all request headers matching a prefix with custom field name transformation.
         * @param prefix the prefix to match (e.g., "X-Custom-")
         * @param fieldNameTransformer function to transform header names to field names
         * @return this builder for chaining
         */
        public Builder addRequestHeadersWithPrefix(String prefix, java.util.function.Function<String, String> fieldNameTransformer) {
            fields.add(new PrefixRequestHeaderField(prefix, fieldNameTransformer));
            return this;
        }

        /**
         * Adds all response headers matching a prefix.
         * @param prefix the prefix to match (e.g., "X-RateLimit-")
         * @return this builder for chaining
         */
        public Builder addResponseHeadersWithPrefix(String prefix) {
            fields.add(new PrefixResponseHeaderField(prefix));
            return this;
        }

        /**
         * Adds all response headers matching a prefix with custom field name transformation.
         * @param prefix the prefix to match (e.g., "X-RateLimit-")
         * @param fieldNameTransformer function to transform header names to field names
         * @return this builder for chaining
         */
        public Builder addResponseHeadersWithPrefix(String prefix, java.util.function.Function<String, String> fieldNameTransformer) {
            fields.add(new PrefixResponseHeaderField(prefix, fieldNameTransformer));
            return this;
        }

        /**
         * Adds all request headers matching a regex pattern.
         * @param regexPattern the regex pattern to match (e.g., "X-.*-Id")
         * @return this builder for chaining
         */
        public Builder addRequestHeadersMatching(String regexPattern) {
            fields.add(new RegexRequestHeaderField(regexPattern));
            return this;
        }

        /**
         * Adds all request headers matching a regex pattern with custom field name transformation.
         * @param regexPattern the regex pattern to match
         * @param fieldNameTransformer function to transform header names to field names
         * @return this builder for chaining
         */
        public Builder addRequestHeadersMatching(String regexPattern, java.util.function.Function<String, String> fieldNameTransformer) {
            fields.add(new RegexRequestHeaderField(regexPattern, fieldNameTransformer));
            return this;
        }

        /**
         * Adds all response headers matching a regex pattern.
         * @param regexPattern the regex pattern to match (e.g., "X-RateLimit-.*")
         * @return this builder for chaining
         */
        public Builder addResponseHeadersMatching(String regexPattern) {
            fields.add(new RegexResponseHeaderField(regexPattern));
            return this;
        }

        /**
         * Adds all response headers matching a regex pattern with custom field name transformation.
         * @param regexPattern the regex pattern to match
         * @param fieldNameTransformer function to transform header names to field names
         * @return this builder for chaining
         */
        public Builder addResponseHeadersMatching(String regexPattern, java.util.function.Function<String, String> fieldNameTransformer) {
            fields.add(new RegexResponseHeaderField(regexPattern, fieldNameTransformer));
            return this;
        }

        /**
         * Adds all request headers matching a regex pattern with custom field name and value transformation.
         * @param regexPattern the regex pattern to match
         * @param fieldNameTransformer function to transform header names to field names
         * @param valueTransformer function to transform header values
         * @return this builder for chaining
         */
        public Builder addRequestHeadersMatching(String regexPattern, java.util.function.Function<String, String> fieldNameTransformer, java.util.function.Function<String, String> valueTransformer) {
            fields.add(new RegexRequestHeaderField(regexPattern, fieldNameTransformer, valueTransformer));
            return this;
        }

        /**
         * Adds all response headers matching a regex pattern with custom field name and value transformation.
         * @param regexPattern the regex pattern to match
         * @param fieldNameTransformer function to transform header names to field names
         * @param valueTransformer function to transform header values
         * @return this builder for chaining
         */
        public Builder addResponseHeadersMatching(String regexPattern, java.util.function.Function<String, String> fieldNameTransformer, java.util.function.Function<String, String> valueTransformer) {
            fields.add(new RegexResponseHeaderField(regexPattern, fieldNameTransformer, valueTransformer));
            return this;
        }

        /**
         * Adds all request headers with a prefix and custom field name and value transformation.
         * @param prefix the prefix to match
         * @param fieldNameTransformer function to transform header names to field names
         * @param valueTransformer function to transform header values
         * @return this builder for chaining
         */
        public Builder addRequestHeadersWithPrefix(String prefix, java.util.function.Function<String, String> fieldNameTransformer, java.util.function.Function<String, String> valueTransformer) {
            fields.add(new PrefixRequestHeaderField(prefix, fieldNameTransformer, valueTransformer));
            return this;
        }

        /**
         * Adds all response headers with a prefix and custom field name and value transformation.
         * @param prefix the prefix to match
         * @param fieldNameTransformer function to transform header names to field names
         * @param valueTransformer function to transform header values
         * @return this builder for chaining
         */
        public Builder addResponseHeadersWithPrefix(String prefix, java.util.function.Function<String, String> fieldNameTransformer, java.util.function.Function<String, String> valueTransformer) {
            fields.add(new PrefixResponseHeaderField(prefix, fieldNameTransformer, valueTransformer));
            return this;
        }

        /**
         * Excludes request headers matching a regex pattern (logs all others).
         * @param excludePattern the regex pattern for headers to exclude
         * @return this builder for chaining
         */
        public Builder excludeRequestHeadersMatching(String excludePattern) {
            fields.add(new ExcludeRequestHeaderField(excludePattern));
            return this;
        }

        /**
         * Excludes request headers matching a regex pattern with custom transformers.
         * @param excludePattern the regex pattern for headers to exclude
         * @param fieldNameTransformer function to transform header names to field names
         * @param valueTransformer function to transform header values
         * @return this builder for chaining
         */
        public Builder excludeRequestHeadersMatching(String excludePattern, java.util.function.Function<String, String> fieldNameTransformer, java.util.function.Function<String, String> valueTransformer) {
            fields.add(new ExcludeRequestHeaderField(excludePattern, fieldNameTransformer, valueTransformer));
            return this;
        }

        /**
         * Excludes response headers matching a regex pattern (logs all others).
         * @param excludePattern the regex pattern for headers to exclude
         * @return this builder for chaining
         */
        public Builder excludeResponseHeadersMatching(String excludePattern) {
            fields.add(new ExcludeResponseHeaderField(excludePattern));
            return this;
        }

        /**
         * Excludes response headers matching a regex pattern with custom transformers.
         * @param excludePattern the regex pattern for headers to exclude
         * @param fieldNameTransformer function to transform header names to field names
         * @param valueTransformer function to transform header values
         * @return this builder for chaining
         */
        public Builder excludeResponseHeadersMatching(String excludePattern, java.util.function.Function<String, String> fieldNameTransformer, java.util.function.Function<String, String> valueTransformer) {
            fields.add(new ExcludeResponseHeaderField(excludePattern, fieldNameTransformer, valueTransformer));
            return this;
        }

        /**
         * Adds a computed field.
         * @param field the computed field to add
         * @return this builder for chaining
         */
        public Builder addComputedField(ComputedField field) {
            fields.add(field);
            return this;
        }
        
        /**
         * Adds a custom log field.
         * @param field the custom field to add
         * @return this builder for chaining
         */
        public Builder addCustomField(LogField field) {
            fields.add(field);
            return this;
        }
        
        /**
         * Removes a field from the configuration.
         * @param field the field to remove
         * @return this builder for chaining
         */
        public Builder removeField(LogField field) {
            fields.remove(field);
            return this;
        }
        
        /**
         * Sets strict standards compliance mode.
         * @param strict whether to enable strict compliance
         * @return this builder for chaining
         */
        public Builder strictStandardsCompliance(boolean strict) {
            this.strictStandardsCompliance = strict;
            return this;
        }
        
        /**
         * Adds a custom field name mapping.
         * @param originalName the original field name
         * @param customName the custom field name
         * @return this builder for chaining
         */
        public Builder mapField(String originalName, String customName) {
            customFieldMappings.put(originalName, customName);
            return this;
        }
        
        /**
         * Configures the fields to be suitable for CLF format.
         * @return this builder for chaining
         */
        public Builder forClfFormat() {
            fields.clear();
            addStandardField(StandardField.CLIENT_IP);
            addStandardField(StandardField.TIMESTAMP);
            addStandardField(StandardField.METHOD);
            addStandardField(StandardField.URI);
            addStandardField(StandardField.STATUS);
            addStandardField(StandardField.BYTES);
            strictStandardsCompliance(true);
            return this;
        }
        
        /**
         * Configures the fields to be suitable for ELF format.
         * @return this builder for chaining
         */
        public Builder forElfFormat() {
            fields.clear();
            addStandardField(StandardField.CLIENT_IP);
            addStandardField(StandardField.TIMESTAMP);
            addStandardField(StandardField.METHOD);
            addStandardField(StandardField.URI);
            addStandardField(StandardField.STATUS);
            addStandardField(StandardField.BYTES);
            addStandardField(StandardField.REFERER);
            addStandardField(StandardField.USER_AGENT);
            return this;
        }
        
        /**
         * Configures the fields to be suitable for JSON format.
         * @return this builder for chaining
         */
        public Builder forJsonFormat() {
            fields.clear();
            addStandardFields();
            return this;
        }
        
        /**
         * Builds the LogFieldConfiguration.
         * @return the constructed configuration
         */
        public LogFieldConfiguration build() {
            return new LogFieldConfiguration(this);
        }
    }
}