package org.littleshoot.proxy.extras.logging;

/**
 * Factory class that provides pre-made configurations for API management. These configurations
 * focus on rate limiting, correlation IDs, versioning, and API-specific metrics according to
 * state-of-the-art practices.
 */
public class APIManagementConfig {

  /**
   * Creates a comprehensive API management configuration. Includes API versioning, rate limiting,
   * correlation tracking, and error analysis with modern headers for distributed tracing and
   * microservices.
   *
   * @return a comprehensive API management-focused log field configuration
   */
  public static LogFieldConfiguration create() {
    return LogFieldConfiguration.builder()
        // Standard API fields
        .addStandardField(StandardField.TIMESTAMP)
        .addStandardField(StandardField.CLIENT_IP)
        .addStandardField(StandardField.REMOTE_IP)
        .addStandardField(StandardField.METHOD)
        .addStandardField(StandardField.URI)
        .addStandardField(StandardField.STATUS)
        .addStandardField(StandardField.BYTES)
        .addStandardField(StandardField.DURATION)
        .addStandardField(StandardField.USER_AGENT)

        // API versioning headers
        .addRequestHeader("API-Version", "api_version")
        .addRequestHeader("Accept-Version", "accept_version")
        .addRequestHeader("X-API-Version", "x_api_version")
        .addRequestHeader("X-Client-Version", "x_client_version")
        .addResponseHeader("API-Version", "resp_api_version")
        .addResponseHeader("X-API-Version", "resp_x_api_version")
        .addResponseHeader("X-Current-Version", "x_current_version")

        // Correlation and tracing headers
        .addRequestHeader("X-Request-ID", "request_id")
        .addRequestHeader("X-Correlation-ID", "correlation_id")
        .addRequestHeader("X-Trace-ID", "trace_id")
        .addRequestHeader("X-B3-TraceId", "b3_traceid")
        .addRequestHeader("X-B3-SpanId", "b3_spanid")
        .addRequestHeader("X-B3-ParentSpanId", "b3_parentspanid")
        .addRequestHeader("X-Request-Id", "request_id_alt")
        .addRequestHeader("traceparent", "traceparent")
        .addRequestHeader("tracestate", "tracestate")
        .addRequestHeader("X-Transaction-ID", "transaction_id")
        .addRequestHeader("X-Session-ID", "session_id")
        .addResponseHeader("X-Request-ID", "response_request_id")
        .addResponseHeader("X-Correlation-ID", "response_correlation_id")
        .addResponseHeader("X-Trace-ID", "response_trace_id")
        .addResponseHeader("X-B3-TraceId", "response_b3_traceid")
        .addResponseHeader("X-B3-SpanId", "response_b3_spanid")
        .addResponseHeader("X-Session-ID", "response_session_id")

        // Rate limiting headers (request)
        .addRequestHeader("X-Rate-Limit-Key", "rate_limit_key")
        .addRequestHeader("X-Client-ID", "client_id")
        .addRequestHeader("X-Application-ID", "application_id")
        .addRequestHeader("X-Quota-Limit", "quota_limit")

        // Rate limiting headers (response)
        .addResponseHeader("X-RateLimit-Limit", "rate_limit")
        .addResponseHeader("X-RateLimit-Remaining", "rate_limit_remaining")
        .addResponseHeader("X-RateLimit-Reset", "rate_limit_reset")
        .addResponseHeader("X-RateLimit-Retry-After", "rate_limit_retry_after")
        .addResponseHeader("Retry-After", "retry_after")
        .addResponseHeader("X-Quota-Limit", "resp_quota_limit")
        .addResponseHeader("X-Quota-Used", "resp_quota_used")
        .addResponseHeader("X-Quota-Remaining", "resp_quota_remaining")

        // Authentication and authorization
        .addRequestHeader("Authorization", "auth_type")
        .addRequestHeader("X-API-Key", "api_key_present")
        .addRequestHeader("X-Auth-Token", "auth_token")
        .addRequestHeader("X-Service-Token", "service_token")

        // Client identification
        .addRequestHeader("X-Client-Name", "client_name")
        .addRequestHeader("X-Client-Type", "client_type")
        .addRequestHeader("User-Agent", "user_agent")
        .addRequestHeader("X-Client-Platform", "client_platform")
        .addRequestHeader("X-Client-SDK", "client_sdk")

        // API-specific response headers
        .addResponseHeader("Content-Type", "content_type")
        .addResponseHeader("X-Response-Format", "response_format")
        .addResponseHeader("X-Error-Code", "error_code")
        .addResponseHeader("X-Error-Message", "error_message")
        .addResponseHeader("X-Error-Detail", "error_detail")
        .addResponseHeader("X-Validation-Error", "validation_error")

        // Load balancing and service routing
        .addResponseHeader("X-Server-ID", "server_id")
        .addResponseHeader("X-Datacenter", "datacenter")
        .addResponseHeader("X-Region", "region")
        .addResponseHeader("X-Upstream-Server", "upstream_server")
        .addResponseHeader("X-Upstream-Status", "upstream_status")
        .addResponseHeader("X-Service-Name", "service_name")
        .addResponseHeader("X-Upstream-Service", "upstream_service")

        // Cache and performance for APIs
        .addRequestHeader("If-None-Match", "if_none_match")
        .addResponseHeader("ETag", "etag")
        .addResponseHeader("Cache-Control", "cache_control")
        .addResponseHeader("Age", "cache_age")

        // Computed fields for API analytics
        .addComputedField(ComputedField.AUTHENTICATION_TYPE)
        .addComputedField(ComputedField.RESPONSE_TIME_CATEGORY)
        .addComputedField(ComputedField.REQUEST_SIZE)

        // W3C compliance for API management
        .strictStandardsCompliance(true)
        .build();
  }

  /**
   * Creates a lightweight API management configuration. Focuses on essential API tracking with
   * minimal overhead.
   *
   * @return a lightweight API management log field configuration
   */
  public static LogFieldConfiguration createLightweight() {
    return LogFieldConfiguration.builder()
        // Essential API info
        .addStandardField(StandardField.TIMESTAMP)
        .addStandardField(StandardField.CLIENT_IP)
        .addStandardField(StandardField.METHOD)
        .addStandardField(StandardField.URI)
        .addStandardField(StandardField.STATUS)
        .addStandardField(StandardField.DURATION)

        // Essential API headers
        .addRequestHeader("X-Request-ID", "x_request_id")
        .addRequestHeader("X-API-Version", "x_api_version")
        .addRequestHeader("Authorization", "authorization")
        .addResponseHeader("X-Request-ID", "resp_x_request_id")
        .addResponseHeader("X-RateLimit-Remaining", "x_ratelimit_remaining")
        .addResponseHeader("X-Error-Code", "x_error_code")

        // Essential computed fields
        .addComputedField(ComputedField.AUTHENTICATION_TYPE)
        .addComputedField(ComputedField.RESPONSE_TIME_CATEGORY)
        .strictStandardsCompliance(false)
        .build();
  }

  /**
   * Creates a rate limiting focused configuration. Concentrates on rate limiting metrics and quota
   * tracking.
   *
   * @return a rate limiting focused log field configuration
   */
  public static LogFieldConfiguration createRateLimitingFocused() {
    return LogFieldConfiguration.builder()
        // Basic request info
        .addStandardField(StandardField.TIMESTAMP)
        .addStandardField(StandardField.CLIENT_IP)
        .addStandardField(StandardField.METHOD)
        .addStandardField(StandardField.URI)
        .addStandardField(StandardField.STATUS)
        .addStandardField(StandardField.DURATION)

        // Rate limiting headers
        .addRequestHeader("X-Rate-Limit-Key", "x_rate_limit_key")
        .addRequestHeader("X-Client-ID", "x_client_id")
        .addRequestHeader("X-API-Key", "x_api_key")
        .addResponseHeader("X-RateLimit-Limit", "x_ratelimit_limit")
        .addResponseHeader("X-RateLimit-Remaining", "x_ratelimit_remaining")
        .addResponseHeader("X-RateLimit-Reset", "x_ratelimit_reset")
        .addResponseHeader("X-RateLimit-Retry-After", "x_ratelimit_retry_after")
        .addResponseHeader("Retry-After", "retry_after")
        .addResponseHeader("X-Quota-Limit", "x_quota_limit")
        .addResponseHeader("X-Quota-Used", "x_quota_used")
        .addResponseHeader("X-Quota-Remaining", "x_quota_remaining")

        // Authentication for rate limiting
        .addRequestHeader("Authorization", "authorization")

        // Computed fields
        .addComputedField(ComputedField.AUTHENTICATION_TYPE)
        .strictStandardsCompliance(false)
        .build();
  }

  /**
   * Creates a correlation tracking focused configuration. Focuses on distributed tracing and
   * request correlation across services.
   *
   * @return a correlation tracking focused log field configuration
   */
  public static LogFieldConfiguration createCorrelationFocused() {
    return LogFieldConfiguration.builder()
        // Basic info
        .addStandardField(StandardField.TIMESTAMP)
        .addStandardField(StandardField.CLIENT_IP)
        .addStandardField(StandardField.METHOD)
        .addStandardField(StandardField.URI)
        .addStandardField(StandardField.STATUS)
        .addStandardField(StandardField.DURATION)

        // Correlation and tracing headers
        .addRequestHeader("X-Request-ID", "x_request_id")
        .addRequestHeader("X-Correlation-ID", "x_correlation_id")
        .addRequestHeader("X-Trace-ID", "x_trace_id")
        .addRequestHeader("X-B3-TraceId", "x_b3_traceid")
        .addRequestHeader("X-B3-SpanId", "x_b3_spanid")
        .addRequestHeader("X-B3-ParentSpanId", "x_b3_parentspanid")
        .addRequestHeader("X-Request-Id", "x_request_id_alt")
        .addRequestHeader("X-Transaction-ID", "x_transaction_id")
        .addRequestHeader("X-Session-ID", "x_session_id")
        .addResponseHeader("X-Request-ID", "resp_x_request_id")
        .addResponseHeader("X-Correlation-ID", "resp_x_correlation_id")
        .addResponseHeader("X-Trace-ID", "resp_x_trace_id")
        .addResponseHeader("X-B3-TraceId", "resp_x_b3_traceid")
        .addResponseHeader("X-B3-SpanId", "resp_x_b3_spanid")
        .addResponseHeader("X-Session-ID", "resp_x_session_id")

        // Service routing for correlation
        .addResponseHeader("X-Server-ID", "x_server_id")
        .addResponseHeader("X-Service-Name", "x_service_name")
        .addResponseHeader("X-Upstream-Service", "x_upstream_service")
        .strictStandardsCompliance(false)
        .build();
  }
}
