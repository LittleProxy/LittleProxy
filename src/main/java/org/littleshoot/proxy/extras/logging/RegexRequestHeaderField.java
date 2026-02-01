package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.littleshoot.proxy.FlowContext;

/**
 * Represents a regex-based request header field that matches headers by pattern.
 * This class discovers and logs all request headers matching a given regex pattern.
 */
public class RegexRequestHeaderField implements LogField {

    private final Pattern pattern;
    private final Function<String, String> fieldNameTransformer;
    private final Function<String, String> valueTransformer;
    private final String description;

    /**
     * Creates a new regex-based request header field.
     * @param pattern the regex pattern to match (e.g., Pattern.compile("X-.*-Id"))
     */
    public RegexRequestHeaderField(Pattern pattern) {
        this(pattern, name -> name.toLowerCase().replaceAll("[^a-z0-9]", "_"), value -> value);
    }

    /**
     * Creates a new regex-based request header field with custom field name transformation.
     * @param pattern the regex pattern to match
     * @param fieldNameTransformer function to transform header names to field names
     */
    public RegexRequestHeaderField(Pattern pattern, Function<String, String> fieldNameTransformer) {
        this(pattern, fieldNameTransformer, value -> value);
    }

    /**
     * Creates a new regex-based request header field with custom field name and value transformation.
     * @param pattern the regex pattern to match
     * @param fieldNameTransformer function to transform header names to field names
     * @param valueTransformer function to transform header values
     */
    public RegexRequestHeaderField(Pattern pattern, Function<String, String> fieldNameTransformer,
                                   Function<String, String> valueTransformer) {
        this.pattern = pattern;
        this.fieldNameTransformer = fieldNameTransformer != null ? fieldNameTransformer :
            name -> name.toLowerCase().replaceAll("[^a-z0-9]", "_");
        this.valueTransformer = valueTransformer != null ? valueTransformer : value -> value;
        this.description = "Request headers matching pattern: " + pattern.pattern();
    }

    /**
     * Creates a new regex-based request header field from a regex string.
     * @param regex the regex pattern string to match
     */
    public RegexRequestHeaderField(String regex) {
        this(Pattern.compile(regex));
    }

    /**
     * Creates a new regex-based request header field from a regex string with custom transformer.
     * @param regex the regex pattern string to match
     * @param fieldNameTransformer function to transform header names to field names
     */
    public RegexRequestHeaderField(String regex, Function<String, String> fieldNameTransformer) {
        this(Pattern.compile(regex), fieldNameTransformer);
    }

    /**
     * Creates a new regex-based request header field from a regex string with custom field name and value transformation.
     * @param regex the regex pattern string to match
     * @param fieldNameTransformer function to transform header names to field names
     * @param valueTransformer function to transform header values
     */
    public RegexRequestHeaderField(String regex, Function<String, String> fieldNameTransformer,
                                   Function<String, String> valueTransformer) {
        this(Pattern.compile(regex), fieldNameTransformer, valueTransformer);
    }

    @Override
    public String getName() {
        return "req_" + pattern.pattern().toLowerCase().replaceAll("[^a-z0-9]", "_") + "*";
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String extractValue(FlowContext flowContext, HttpRequest request, HttpResponse response, long duration) {
        // This method is not used directly for pattern fields
        // Instead, use extractMatchingHeaders() to get all matches
        return "-";
    }

    /**
     * Extracts all headers matching the pattern.
     * @param headers the HTTP headers to search
     * @return a map of field names to header values for all matching headers
     */
    public Map<String, String> extractMatchingHeaders(HttpHeaders headers) {
        Map<String, String> matches = new TreeMap<>();
        for (String headerName : headers.names()) {
            Matcher matcher = pattern.matcher(headerName);
            if (matcher.matches()) {
                String value = headers.get(headerName);
                String fieldName = fieldNameTransformer.apply(headerName);
                String transformedValue = value != null ? valueTransformer.apply(value) : "-";
                matches.put(fieldName, transformedValue);
            }
        }
        return matches;
    }

    /**
     * Checks if this field has any matching headers.
     * @param headers the HTTP headers to check
     * @return true if at least one header matches the pattern
     */
    public boolean hasMatches(HttpHeaders headers) {
        for (String headerName : headers.names()) {
            Matcher matcher = pattern.matcher(headerName);
            if (matcher.matches()) {
                return true;
            }
        }
        return false;
    }

    public Pattern getPattern() {
        return pattern;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        RegexRequestHeaderField that = (RegexRequestHeaderField) obj;
        return pattern.pattern().equals(that.pattern.pattern());
    }

    @Override
    public int hashCode() {
        return pattern.pattern().hashCode();
    }

    @Override
    public String toString() {
        return "RegexRequestHeaderField{pattern='" + pattern.pattern() + "'}";
    }
}
