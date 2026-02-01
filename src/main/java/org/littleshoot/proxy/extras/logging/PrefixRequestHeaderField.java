package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.littleshoot.proxy.FlowContext;

/**
 * Represents a pattern-based request header field that matches headers by prefix.
 * This class discovers and logs all request headers starting with a given prefix.
 */
public class PrefixRequestHeaderField implements LogField {

    private final String prefix;
    private final Function<String, String> fieldNameTransformer;
    private final Function<String, String> valueTransformer;
    private final String description;

    /**
     * Creates a new prefix-based request header field.
     * @param prefix the prefix to match (e.g., "X-Custom-")
     */
    public PrefixRequestHeaderField(String prefix) {
        this(prefix, name -> name.toLowerCase().replaceAll("[^a-z0-9]", "_"), value -> value);
    }

    /**
     * Creates a new prefix-based request header field with custom field name transformation.
     * @param prefix the prefix to match (e.g., "X-Custom-")
     * @param fieldNameTransformer function to transform header names to field names
     */
    public PrefixRequestHeaderField(String prefix, Function<String, String> fieldNameTransformer) {
        this(prefix, fieldNameTransformer, value -> value);
    }

    /**
     * Creates a new prefix-based request header field with custom field name and value transformation.
     * @param prefix the prefix to match (e.g., "X-Custom-")
     * @param fieldNameTransformer function to transform header names to field names
     * @param valueTransformer function to transform header values
     */
    public PrefixRequestHeaderField(String prefix, Function<String, String> fieldNameTransformer, 
                                    Function<String, String> valueTransformer) {
        this.prefix = prefix;
        this.fieldNameTransformer = fieldNameTransformer != null ? fieldNameTransformer : 
            name -> name.toLowerCase().replaceAll("[^a-z0-9]", "_");
        this.valueTransformer = valueTransformer != null ? valueTransformer : value -> value;
        this.description = "Request headers matching prefix: " + prefix;
    }

    @Override
    public String getName() {
        return "req_" + prefix.toLowerCase().replaceAll("[^a-z0-9]", "_") + "*";
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
     * Extracts all headers matching the prefix.
     * @param headers the HTTP headers to search
     * @return a map of field names to header values for all matching headers
     */
    public Map<String, String> extractMatchingHeaders(HttpHeaders headers) {
        Map<String, String> matches = new TreeMap<>();
        for (String headerName : headers.names()) {
            if (headerName.startsWith(prefix)) {
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
     * @return true if at least one header matches the prefix
     */
    public boolean hasMatches(HttpHeaders headers) {
        for (String headerName : headers.names()) {
            if (headerName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public String getPrefix() {
        return prefix;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PrefixRequestHeaderField that = (PrefixRequestHeaderField) obj;
        return prefix.equals(that.prefix);
    }

    @Override
    public int hashCode() {
        return prefix.hashCode();
    }

    @Override
    public String toString() {
        return "PrefixRequestHeaderField{prefix='" + prefix + "'}";
    }
}
