package org.littleshoot.proxy.extras.logging;

/**
 * Factory class that provides pre-made configurations for security monitoring. These configurations
 * focus on security-related headers, geo-location tracking, and authentication information
 * according to state-of-the-art practices.
 */
public class SecurityMonitoringConfig {

  /**
   * Creates a comprehensive security monitoring configuration. Includes all modern security
   * headers, authentication tracking, geolocation data, and compliance monitoring.
   *
   * @return a comprehensive security-focused log field configuration
   */
  public static LogFieldConfiguration create() {
    return LogFieldConfiguration.builder()
        // Standard fields
        .addStandardField(StandardField.TIMESTAMP)
        .addStandardField(StandardField.CLIENT_IP)
        .addStandardField(StandardField.REMOTE_IP)
        .addStandardField(StandardField.METHOD)
        .addStandardField(StandardField.URI)
        .addStandardField(StandardField.STATUS)
        .addStandardField(StandardField.HTTP_REQUEST_PROCESSING_TIME)
        .addStandardField(StandardField.USER_AGENT)
        .addStandardField(StandardField.REFERER)

        // Security request headers
        .addRequestHeader("X-Forwarded-For", "forwarded_for")
        .addRequestHeader("X-Real-IP", "real_ip")
        .addRequestHeader("X-Original-URL", "original_url")
        .addRequestHeader("X-Request-ID", "request_id")
        .addRequestHeader("X-Client-ID", "client_id")
        .addRequestHeader("Authorization", "auth_type") // Consider masking sensitive data
        .addRequestHeader("X-Forwarded-Proto", "forwarded_proto")
        .addRequestHeader("Origin", "origin")
        .addRequestHeader("User-Agent", "user_agent")
        .addRequestHeader("X-Content-Security-Policy-Report-Only", "csp_report")
        .addRequestHeader("Sec-Fetch-Dest", "sec_fetch_dest")
        .addRequestHeader("Sec-Fetch-Mode", "sec_fetch_mode")
        .addRequestHeader("Sec-Fetch-Site", "sec_fetch_site")
        .addRequestHeader("Sec-Fetch-User", "sec_fetch_user")
        .addRequestHeader("Sec-Ch-Ua", "sec_ch_ua")

        // Security response headers (critical for audit)
        .addResponseHeader("Content-Security-Policy", "csp")
        .addResponseHeader("Strict-Transport-Security", "hsts")
        .addResponseHeader("X-Content-Type-Options", "x_content_type_options")
        .addResponseHeader("X-Frame-Options", "x_frame_options")
        .addResponseHeader("X-XSS-Protection", "xss_protection")
        .addResponseHeader("Referrer-Policy", "referrer_policy")
        .addResponseHeader("X-Request-ID", "response_request_id")
        .addResponseHeader("X-Content-Security-Policy-Report-Only", "csp_report_only")
        .addResponseHeader("Permissions-Policy", "permissions_policy")
        .addResponseHeader("Cross-Origin-Embedder-Policy", "coep")
        .addResponseHeader("Cross-Origin-Resource-Policy", "corp")
        .addResponseHeader("Cross-Origin-Opener-Policy", "coop")
        .addResponseHeader("Content-Security-Policy-Report-Only", "csp_ro")

        // Computed security fields
        .addComputedField(ComputedField.GEOLOCATION_COUNTRY)
        .addComputedField(ComputedField.AUTHENTICATION_TYPE)
        .addComputedField(ComputedField.REQUEST_SIZE)
        .addComputedField(ComputedField.RESPONSE_TIME_CATEGORY)

        // W3C compliance for security monitoring
        .strictStandardsCompliance(true)
        .build();
  }

  /**
   * Creates a high-security configuration for sensitive applications. Includes additional
   * monitoring headers and stricter tracking for threat detection.
   *
   * @return high-security log field configuration
   */
  public static LogFieldConfiguration createHighSecurity() {
    return LogFieldConfiguration.builder()
        // Standard fields
        .addStandardField(StandardField.TIMESTAMP)
        .addStandardField(StandardField.CLIENT_IP)
        .addStandardField(StandardField.REMOTE_IP)
        .addStandardField(StandardField.METHOD)
        .addStandardField(StandardField.URI)
        .addStandardField(StandardField.STATUS)
        .addStandardField(StandardField.HTTP_REQUEST_PROCESSING_TIME)
        .addStandardField(StandardField.USER_AGENT)
        .addStandardField(StandardField.REFERER)

        // Enhanced security request headers
        .addRequestHeader("X-Forwarded-For", "forwarded_for")
        .addRequestHeader("X-Real-IP", "real_ip")
        .addRequestHeader("X-Original-URL", "original_url")
        .addRequestHeader("X-Request-ID", "request_id")
        .addRequestHeader("X-Client-ID", "client_id")
        .addRequestHeader("Authorization", "auth_type")
        .addRequestHeader("X-Forwarded-Proto", "forwarded_proto")
        .addRequestHeader("X-Forwarded-Host", "forwarded_host")
        .addRequestHeader("X-Forwarded-Server", "forwarded_server")
        .addRequestHeader("X-Session-ID", "session_id")
        .addRequestHeader("X-Device-ID", "device_id")
        .addRequestHeader("X-RateLimit-Limit", "rate_limit")
        .addRequestHeader("X-API-Key", "api_key_present")
        .addRequestHeader("Origin", "origin")

        // Enhanced security response headers
        .addResponseHeader("Content-Security-Policy", "csp")
        .addResponseHeader("Strict-Transport-Security", "hsts")
        .addResponseHeader("X-Content-Type-Options", "x_content_type_options")
        .addResponseHeader("X-Frame-Options", "x_frame_options")
        .addResponseHeader("X-XSS-Protection", "xss_protection")
        .addResponseHeader("Referrer-Policy", "referrer_policy")
        .addResponseHeader("X-Request-ID", "response_request_id")
        .addResponseHeader("Set-Cookie", "set_cookie")
        .addResponseHeader("WWW-Authenticate", "www_authenticate")
        .addResponseHeader("Proxy-Authenticate", "proxy_authenticate")
        .addResponseHeader("X-RateLimit-Limit", "resp_rate_limit")
        .addResponseHeader("X-RateLimit-Remaining", "resp_rate_limit_remaining")
        .addResponseHeader("X-RateLimit-Reset", "resp_rate_limit_reset")
        .addResponseHeader("Permissions-Policy", "permissions_policy")

        // Computed security fields
        .addComputedField(ComputedField.GEOLOCATION_COUNTRY)
        .addComputedField(ComputedField.AUTHENTICATION_TYPE)
        .addComputedField(ComputedField.REQUEST_SIZE)
        .addComputedField(ComputedField.RESPONSE_TIME_CATEGORY)
        .strictStandardsCompliance(true)
        .build();
  }

  /**
   * Creates a basic security configuration for simple monitoring. Focuses on essential security
   * fields with minimal overhead.
   *
   * @return basic security log field configuration
   */
  public static LogFieldConfiguration createBasic() {
    return LogFieldConfiguration.builder()
        .addStandardField(StandardField.TIMESTAMP)
        .addStandardField(StandardField.CLIENT_IP)
        .addStandardField(StandardField.REMOTE_IP)
        .addStandardField(StandardField.METHOD)
        .addStandardField(StandardField.URI)
        .addStandardField(StandardField.STATUS)
        .addStandardField(StandardField.USER_AGENT)
        .addStandardField(StandardField.REFERER)
        .addRequestHeader("X-Forwarded-For", "forwarded_for")
        .addRequestHeader("Authorization", "auth_type")
        .addResponseHeader("Content-Security-Policy", "csp")
        .addResponseHeader("Strict-Transport-Security", "hsts")
        .addResponseHeader("X-Content-Type-Options", "x_content_type_options")
        .addResponseHeader("X-Frame-Options", "x_frame_options")
        .addComputedField(ComputedField.GEOLOCATION_COUNTRY)
        .addComputedField(ComputedField.AUTHENTICATION_TYPE)
        .strictStandardsCompliance(false)
        .build();
  }

  /**
   * Creates a lightweight security monitoring configuration. Focuses on essential security
   * information with minimal overhead.
   *
   * @return a lightweight security-focused log field configuration
   */
  public static LogFieldConfiguration createLightweight() {
    return createBasic();
  }

  /**
   * Creates an authentication-focused configuration. Concentrates on authentication and
   * authorization tracking.
   *
   * @return an authentication-focused log field configuration
   */
  public static LogFieldConfiguration createAuthenticationFocused() {
    return LogFieldConfiguration.builder()
        // Basic request info
        .addStandardField(StandardField.TIMESTAMP)
        .addStandardField(StandardField.CLIENT_IP)
        .addStandardField(StandardField.METHOD)
        .addStandardField(StandardField.URI)
        .addStandardField(StandardField.STATUS)

        // Authentication headers
        .addRequestHeader("Authorization", "authorization")
        .addRequestHeader("X-API-Key", "x_api_key")
        .addRequestHeader("X-Auth-Token", "x_auth_token")
        .addRequestHeader("Cookie", "cookie")
        .addRequestHeader("X-Forwarded-For", "x_forwarded_for")

        // Session tracking
        .addResponseHeader("Set-Cookie", "set_cookie")
        .addResponseHeader("WWW-Authenticate", "www_authenticate")

        // Authentication computed fields
        .addComputedField(ComputedField.AUTHENTICATION_TYPE)
        .strictStandardsCompliance(false)
        .build();
  }
}
