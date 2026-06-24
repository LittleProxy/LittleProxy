package org.littleshoot.proxy.extras.logging.formatter;

import java.util.Map;
import org.littleshoot.proxy.extras.logging.LogFormat;

/**
 * Factory for creating LogEntryFormatter instances based on the requested LogFormat. This factory
 * maintains a registry of formatters and provides a centralized way to obtain the appropriate
 * formatter for a given log format.
 */
public final class LogEntryFormatterFactory {

  private static final Map<LogFormat, LogEntryFormatter> FORMATTERS =
      Map.of(
          LogFormat.CLF, new ClfFormatter(),
          LogFormat.ELF, new ElfFormatter(),
          LogFormat.W3C, new W3cFormatter(),
          LogFormat.JSON, new JsonFormatter(),
          LogFormat.LTSV, new LtsvFormatter(),
          LogFormat.CSV, new CsvFormatter(),
          LogFormat.SQUID, new SquidFormatter(),
          LogFormat.HAPROXY, new HaproxyFormatter(),
          LogFormat.KEYVALUE, new KeyValueFormatter());

  private LogEntryFormatterFactory() {
    // Utility class - prevent instantiation
  }

  /**
   * Returns the formatter implementation for the specified log format.
   *
   * @param format the log format enum value
   * @return the corresponding LogEntryFormatter implementation
   * @throws IllegalArgumentException if the format is null or not supported
   */
  public static LogEntryFormatter getFormatter(LogFormat format) {
    if (format == null) {
      throw new IllegalArgumentException("Log format cannot be null");
    }

    LogEntryFormatter formatter = FORMATTERS.get(format);
    if (formatter == null) {
      throw new IllegalArgumentException("Unsupported log format: " + format);
    }

    return formatter;
  }
}
