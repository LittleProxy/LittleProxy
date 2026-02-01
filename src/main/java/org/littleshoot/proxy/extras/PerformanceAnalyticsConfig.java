package org.littleshoot.proxy.extras;

/**
 * Factory class that provides pre-made configurations for performance analytics.
 * These configurations focus on CDN performance, caching metrics, compression,
 * and server timing information according to state-of-the-art practices.
 */
public class PerformanceAnalyticsConfig {
    
    /**
     * Creates a comprehensive performance analytics configuration.
     * Includes timing metrics, cache performance, compression, CDN information,
     * and modern server timing headers for detailed performance analysis.
     * 
     * @return a comprehensive performance-focused log field configuration
     */
    public static LogFieldConfiguration create() {
        return LogFieldConfiguration.builder()
                // Basic performance metrics
                .addStandardField(StandardField.TIMESTAMP)
                .addStandardField(StandardField.CLIENT_IP)
                .addStandardField(StandardField.REMOTE_IP)
                .addStandardField(StandardField.METHOD)
                .addStandardField(StandardField.URI)
                .addStandardField(StandardField.STATUS)
                .addStandardField(StandardField.BYTES)
                .addStandardField(StandardField.DURATION)
                
                // Performance request headers
                .addRequestHeader("Accept-Encoding", "accept_encoding")
                .addRequestHeader("If-None-Match", "if_none_match")
                .addRequestHeader("If-Modified-Since", "if_modified_since")
                .addRequestHeader("Cache-Control", "cache_control")
                .addRequestHeader("Pragma", "pragma")
                .addRequestHeader("Purpose", "purpose")
                .addRequestHeader("Sec-Purpose", "sec_purpose")
                
                // Performance response headers (CDN/Caching)
                .addResponseHeader("Cache-Control", "cache_control")
                .addResponseHeader("X-Cache", "cache_status")
                .addResponseHeader("X-Cache-Hits", "cache_hits")
                .addResponseHeader("Age", "cache_age")
                .addResponseHeader("Server-Timing", "server_timing")
                .addResponseHeader("Content-Encoding", "content_encoding")
                .addResponseHeader("ETag", "etag")
                .addResponseHeader("Last-Modified", "last_modified")
                .addResponseHeader("Vary", "vary")
                .addResponseHeader("Content-Length", "content_length")
                .addResponseHeader("Content-Type", "content_type")
                
                // CDN-specific headers
                .addRequestHeader("X-Forwarded-For", "forwarded_for")
                .addRequestHeader("X-Real-IP", "real_ip")
                .addRequestHeader("CF-Connecting-IP", "cf_connecting_ip")
                .addRequestHeader("Cloudflare-View", "cloudflare_view")
                
                .addResponseHeader("CF-Cache-Status", "cloudflare_cache")
                .addResponseHeader("CF-Ray", "cloudflare_ray")
                .addResponseHeader("X-Served-By", "served_by")
                .addResponseHeader("X-Edge-Location", "edge_location")
                .addResponseHeader("X-Cache-Key", "cache_key")
                .addResponseHeader("X-Varnish", "varnish")
                .addResponseHeader("X-Akamai-Cache", "akamai_cache")
                .addResponseHeader("X-Fastly-Debug", "fastly_debug")
                .addResponseHeader("X-Timer", "timer")
                
                // Computed performance fields
                .addComputedField(ComputedField.CACHE_HIT_RATIO)
                .addComputedField(ComputedField.COMPRESSION_RATIO)
                .addComputedField(ComputedField.RESPONSE_TIME_CATEGORY)
                .addComputedField(ComputedField.REQUEST_SIZE)
                
                // W3C compliance for performance monitoring
                .strictStandardsCompliance(true)
                .build();
    }
    
    /**
     * Creates a CDN-focused configuration.
     * Concentrates on CDN performance and edge caching metrics.
     * 
     * @return a CDN-focused log field configuration
     */
    public static LogFieldConfiguration createCdnFocused() {
        return LogFieldConfiguration.builder()
                // Basic request info
                .addStandardField(StandardField.TIMESTAMP)
                .addStandardField(StandardField.CLIENT_IP)
                .addStandardField(StandardField.METHOD)
                .addStandardField(StandardField.URI)
                .addStandardField(StandardField.STATUS)
                .addStandardField(StandardField.BYTES)
                .addStandardField(StandardField.DURATION)
                
                // CDN-specific headers
                .addRequestHeader("CF-Connecting-IP", "cf_connecting_ip")
                .addRequestHeader("X-Forwarded-For", "forwarded_for")
                .addRequestHeader("X-Real-IP", "real_ip")
                .addRequestHeader("Cloudflare-View", "cloudflare_view")
                
                .addResponseHeader("X-Cache", "cache_status")
                .addResponseHeader("X-Cache-Hits", "cache_hits")
                .addResponseHeader("X-Served-By", "served_by")
                .addResponseHeader("X-Edge-Location", "edge_location")
                .addResponseHeader("CF-Cache-Status", "cloudflare_cache")
                .addResponseHeader("CF-Ray", "cloudflare_ray")
                .addResponseHeader("Server-Timing", "server_timing")
                
                // Computed fields
                .addComputedField(ComputedField.CACHE_HIT_RATIO)
                .addComputedField(ComputedField.RESPONSE_TIME_CATEGORY)
                
                .strictStandardsCompliance(false)
                .build();
    }
    
    /**
     * Creates a cache-focused configuration.
     * Focuses on cache performance and hit/miss metrics.
     * 
     * @return a cache-focused log field configuration
     */
    public static LogFieldConfiguration createCacheFocused() {
        return LogFieldConfiguration.builder()
                // Basic info
                .addStandardField(StandardField.TIMESTAMP)
                .addStandardField(StandardField.CLIENT_IP)
                .addStandardField(StandardField.METHOD)
                .addStandardField(StandardField.URI)
                .addStandardField(StandardField.STATUS)
                .addStandardField(StandardField.BYTES)
                
                // Cache headers
                .addRequestHeader("If-None-Match", "if_none_match")
                .addRequestHeader("If-Modified-Since", "if_modified_since")
                .addRequestHeader("Cache-Control", "cache_control")
                
                .addResponseHeader("Cache-Control", "cache_control")
                .addResponseHeader("ETag", "etag")
                .addResponseHeader("Last-Modified", "last_modified")
                .addResponseHeader("Age", "cache_age")
                .addResponseHeader("X-Cache", "cache_status")
                .addResponseHeader("X-Cache-Hits", "cache_hits")
                .addResponseHeader("X-Varnish", "varnish")
                
                // Computed cache fields
                .addComputedField(ComputedField.CACHE_HIT_RATIO)
                
                .strictStandardsCompliance(false)
                .build();
    }
    
    /**
     * Creates a compression-focused configuration.
     * Concentrates on compression performance and efficiency.
     * 
     * @return a compression-focused log field configuration
     */
    public static LogFieldConfiguration createCompressionFocused() {
        return LogFieldConfiguration.builder()
                // Basic info
                .addStandardField(StandardField.TIMESTAMP)
                .addStandardField(StandardField.CLIENT_IP)
                .addStandardField(StandardField.METHOD)
                .addStandardField(StandardField.URI)
                .addStandardField(StandardField.STATUS)
                .addStandardField(StandardField.BYTES)
                .addStandardField(StandardField.DURATION)
                
                // Compression headers
                .addRequestHeader("Accept-Encoding", "accept_encoding")
                .addResponseHeader("Content-Encoding", "content_encoding")
                .addResponseHeader("Vary", "vary")
                .addResponseHeader("Content-Type", "content_type")
                .addResponseHeader("Content-Length", "content_length")
                
                // Computed compression fields
                .addComputedField(ComputedField.COMPRESSION_RATIO)
                .addComputedField(ComputedField.REQUEST_SIZE)
                
                .strictStandardsCompliance(false)
                .build();
    }
}