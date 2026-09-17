package org.littleshoot.proxy.impl;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * Helpers for {@link ServerConnectionPool} implementations to read typed values from the option map
 * of a {@link ServerConnectionPoolContext}.
 *
 * <p>Option values may arrive as typed objects (set programmatically through the bootstrap) or as
 * raw {@link String}s (read from a properties file). Each converter accepts both forms and fails
 * fast with an {@link IllegalArgumentException} naming the offending key when a value is invalid.
 */
public final class PoolConfigUtils {

  private PoolConfigUtils() {}

  /**
   * Reads an integer option.
   *
   * @param key the option key, used in error messages
   * @param value the raw option value
   * @return the integer value
   * @throws IllegalArgumentException if the value is not a number (or numeric string), a
   *     non-integral number, or a number outside the {@code int} range
   */
  public static int intValue(String key, @Nullable Object value) {
    if (value instanceof Number) {
      double d = ((Number) value).doubleValue();
      if (Double.isFinite(d)
          && d == Math.rint(d)
          && d >= Integer.MIN_VALUE
          && d <= Integer.MAX_VALUE) {
        return (int) d;
      }
      throw new IllegalArgumentException(
          "Invalid value for option '"
              + key
              + "': '"
              + value
              + "' is not an integer within the int range");
    }
    if (value instanceof String) {
      String trimmed = ((String) value).trim();
      try {
        return Integer.parseInt(trimmed);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Invalid value for option '" + key + "': '" + trimmed + "' is not an integer", e);
      }
    }
    throw invalid(key, value, "an integer");
  }

  /**
   * Reads a boolean option.
   *
   * @param key the option key, used in error messages
   * @param value the raw option value
   * @return the boolean value
   * @throws IllegalArgumentException if the value is not a boolean or a string other than
   *     case-insensitive {@code "true"} or {@code "false"}
   */
  public static boolean booleanValue(String key, @Nullable Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof String) {
      String trimmed = ((String) value).trim();
      if ("true".equalsIgnoreCase(trimmed)) {
        return true;
      }
      if ("false".equalsIgnoreCase(trimmed)) {
        return false;
      }
      throw new IllegalArgumentException(
          "Invalid value for option '"
              + key
              + "': '"
              + trimmed
              + "' is not a boolean (use 'true' or 'false')");
    }
    throw invalid(key, value, "a boolean");
  }

  /**
   * Reads a duration option. String values are parsed as ISO-8601 durations (for example {@code
   * "PT90S"}); a plain number is interpreted as seconds.
   *
   * @param key the option key, used in error messages
   * @param value the raw option value
   * @return the duration value
   * @throws IllegalArgumentException if the value is not a duration
   */
  public static Duration durationValue(String key, @Nullable Object value) {
    if (value instanceof Duration) {
      return (Duration) value;
    }
    if (value instanceof Number) {
      return Duration.ofSeconds(((Number) value).longValue());
    }
    if (value instanceof String) {
      String trimmed = ((String) value).trim();
      try {
        return Duration.parse(trimmed);
      } catch (DateTimeParseException e) {
        // fall through to the seconds interpretation below
      }
      try {
        return Duration.ofSeconds(Long.parseLong(trimmed));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Invalid value for option '"
                + key
                + "': '"
                + trimmed
                + "' is not a duration (use ISO-8601 or a number of seconds)",
            e);
      }
    }
    throw invalid(key, value, "a duration");
  }

  private static IllegalArgumentException invalid(String key, Object value, String expected) {
    return new IllegalArgumentException(
        "Invalid value for option '"
            + key
            + "': '"
            + value
            + "' is not "
            + expected
            + " (option values may be typed or strings)");
  }
}
