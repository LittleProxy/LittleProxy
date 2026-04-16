package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.extras.logging.geo.GeoCountryResolver;
import org.littleshoot.proxy.extras.logging.geo.GeoCountryResolverFactory;

/**
 * Enumeration of computed log fields that derive their values from other request/response data.
 * These fields provide analytics and monitoring capabilities by calculating metrics or extracting
 * derived information.
 */
public enum ComputedField implements LogField {
  CACHE_HIT_RATIO("cache_hit_ratio", "Ratio of cache hits (simplified implementation)"),
  COMPRESSION_RATIO("compression_ratio", "Compression ratio based on content encoding"),
  GEOLOCATION_COUNTRY("geolocation_country", "Country code based on client IP geolocation"),
  REQUEST_SIZE("request_size", "Total request size in bytes"),
  AUTHENTICATION_TYPE("authentication_type", "Type of authentication used (basic/bearer/none)");

  private final String name;
  private final String description;

  private static final GeoCountryResolver GEO_COUNTRY_RESOLVER =
      GeoCountryResolverFactory.getResolver();

  ComputedField(String name, String description) {
    this.name = name;
    this.description = description;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String extractValue(FlowContext flowContext, HttpRequest request, HttpResponse response) {
    switch (this) {
      case CACHE_HIT_RATIO:
        return extractCacheHitRatio(request, response);

      case COMPRESSION_RATIO:
        return extractCompressionRatio(request, response);

      case GEOLOCATION_COUNTRY:
        return extractGeolocationCountry(flowContext);

      case REQUEST_SIZE:
        return extractRequestSize(request);

      case AUTHENTICATION_TYPE:
        return extractAuthenticationType(request);

      default:
        return "-";
    }
  }

  private String extractCacheHitRatio(HttpRequest request, HttpResponse response) {
    if (response == null || response.headers() == null) {
      return "0.5";
    }
    // Simplified implementation - in reality this would integrate with cache system
    String cacheControl = response.headers().get("Cache-Control");
    String age = response.headers().get("Age");

    if (cacheControl != null && cacheControl.contains("no-cache")) {
      return "0.0";
    }

    if (age != null && !age.equals("0")) {
      return "1.0"; // Assume cached if age > 0
    }

    return "0.5"; // Default assumption
  }

  private String extractCompressionRatio(HttpRequest request, HttpResponse response) {
    if (response == null || response.headers() == null) {
      return "1.0";
    }
    String contentEncoding = response.headers().get("Content-Encoding");
    String contentLength = response.headers().get("Content-Length");

    if (contentEncoding == null || contentLength == null) {
      return "1.0"; // No compression
    }

    // Simplified calculation - would need actual compressed vs uncompressed sizes
    if (contentEncoding.contains("gzip") || contentEncoding.contains("deflate")) {
      return "0.3"; // Assume 70% compression
    }

    return "1.0";
  }

  private String extractGeolocationCountry(FlowContext flowContext) {
    try {
      if (flowContext.getClientAddress() != null) {
        return GEO_COUNTRY_RESOLVER.resolveCountryCode(flowContext.getClientAddress().getAddress());
      }
    } catch (Exception e) {
      return GeoCountryResolver.UNKNOWN;
    }

    return GeoCountryResolver.UNKNOWN;
  }

  private String extractRequestSize(HttpRequest request) {
    String contentLength = request.headers().get("Content-Length");
    if (contentLength != null) {
      return contentLength;
    }

    // Estimate request size from headers and URI
    int estimatedSize = request.uri().length();
    estimatedSize += request.method().name().length();
    estimatedSize += request.protocolVersion().text().length();

    // Add headers size (rough estimate)
    for (String name : request.headers().names()) {
      estimatedSize += name.length();
      for (String value : request.headers().getAll(name)) {
        estimatedSize += value.length() + 2; // +2 for ": " separator
      }
    }

    return String.valueOf(estimatedSize);
  }

  private String extractAuthenticationType(HttpRequest request) {
    String authHeader = request.headers().get("Authorization");

    if (authHeader == null) {
      return "none";
    }

    if (authHeader.toLowerCase().startsWith("basic ")) {
      return "basic";
    } else if (authHeader.toLowerCase().startsWith("bearer ")) {
      return "bearer";
    } else if (authHeader.toLowerCase().startsWith("digest ")) {
      return "digest";
    } else {
      return "other";
    }
  }
}
