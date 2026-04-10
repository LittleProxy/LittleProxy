package org.littleshoot.proxy.extras.logging;

import java.util.function.Function;

/** Utility class for header value transformations in logging. */
public final class HeaderFieldTransformers {

  private HeaderFieldTransformers() {}

  /** Returns "present" if the value is not null or empty, otherwise "-". */
  public static Function<String, String> presenceOnly() {
    return value -> (value != null && !value.isBlank()) ? "present" : "-";
  }

  /** Fully masks the value with "****". */
  public static Function<String, String> fullMask() {
    return value -> (value != null && !value.isBlank()) ? "****" : "-";
  }

  /**
   * Partially masks all but the first and last 4 characters. Shows prefix+suffix for
   * identification.
   */
  public static Function<String, String> partialMask() {
    return value -> {
      if (value == null || value.isBlank()) {
        return "-";
      }
      int length = value.length();
      if (length <= 8) {
        return "****";
      }
      return value.substring(0, 4) + "****" + value.substring(length - 4);
    };
  }

  /** Masks with a custom marker. Useful for indicating sensitive data is present. */
  public static Function<String, String> masked(String marker) {
    return value -> (value != null && !value.isBlank()) ? marker : "-";
  }
}
