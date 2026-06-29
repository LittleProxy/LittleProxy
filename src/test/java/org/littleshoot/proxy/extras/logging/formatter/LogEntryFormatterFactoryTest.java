package org.littleshoot.proxy.extras.logging.formatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.extras.logging.LogFormat;

class LogEntryFormatterFactoryTest {

  @Test
  void testGetFormatterForJson() {
    LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(LogFormat.JSON);

    assertThat(formatter).isInstanceOf(JsonFormatter.class);
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.JSON);
  }

  @Test
  void testGetFormatterForClf() {
    LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(LogFormat.CLF);

    assertThat(formatter).isInstanceOf(ClfFormatter.class);
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.CLF);
  }

  @Test
  void testGetFormatterForElf() {
    LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(LogFormat.ELF);

    assertThat(formatter).isInstanceOf(ElfFormatter.class);
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.ELF);
  }

  @Test
  void testGetFormatterForCsv() {
    LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(LogFormat.CSV);

    assertThat(formatter).isInstanceOf(CsvFormatter.class);
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.CSV);
  }

  @Test
  void testGetFormatterForLtsv() {
    LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(LogFormat.LTSV);

    assertThat(formatter).isInstanceOf(LtsvFormatter.class);
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.LTSV);
  }

  @Test
  void testGetFormatterForKeyValue() {
    LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(LogFormat.KEYVALUE);

    assertThat(formatter).isInstanceOf(KeyValueFormatter.class);
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.KEYVALUE);
  }

  @Test
  void testGetFormatterForSquid() {
    LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(LogFormat.SQUID);

    assertThat(formatter).isInstanceOf(SquidFormatter.class);
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.SQUID);
  }

  @Test
  void testGetFormatterForHaproxy() {
    LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(LogFormat.HAPROXY);

    assertThat(formatter).isInstanceOf(HaproxyFormatter.class);
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.HAPROXY);
  }

  @Test
  void testGetFormatterForW3c() {
    LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(LogFormat.W3C);

    assertThat(formatter).isInstanceOf(W3cFormatter.class);
    assertThat(formatter.getSupportedFormat()).isEqualTo(LogFormat.W3C);
  }

  @Test
  void testGetFormatterReturnsSameInstance() {
    LogEntryFormatter formatter1 = LogEntryFormatterFactory.getFormatter(LogFormat.JSON);
    LogEntryFormatter formatter2 = LogEntryFormatterFactory.getFormatter(LogFormat.JSON);

    assertThat(formatter1).isSameAs(formatter2);
  }

  @Test
  void testGetFormatterWithNull() {
    assertThatThrownBy(() -> LogEntryFormatterFactory.getFormatter(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Log format cannot be null");
  }

  @Test
  void testAllFormatsCovered() {
    // Verify that all LogFormat enum values have a corresponding formatter
    for (LogFormat format : LogFormat.values()) {
      LogEntryFormatter formatter = LogEntryFormatterFactory.getFormatter(format);
      assertThat(formatter).as("Formatter for " + format + " should not be null").isNotNull();
      assertThat(formatter.getSupportedFormat())
          .as("Formatter for " + format + " should support the correct format")
          .isEqualTo(format);
    }
  }

  @Test
  void testFormatterTypeCorrectness() {
    // Verify each format returns the correct formatter type
    assertThat(LogEntryFormatterFactory.getFormatter(LogFormat.JSON))
        .isExactlyInstanceOf(JsonFormatter.class);
    assertThat(LogEntryFormatterFactory.getFormatter(LogFormat.CLF))
        .isExactlyInstanceOf(ClfFormatter.class);
    assertThat(LogEntryFormatterFactory.getFormatter(LogFormat.ELF))
        .isExactlyInstanceOf(ElfFormatter.class);
    assertThat(LogEntryFormatterFactory.getFormatter(LogFormat.CSV))
        .isExactlyInstanceOf(CsvFormatter.class);
    assertThat(LogEntryFormatterFactory.getFormatter(LogFormat.LTSV))
        .isExactlyInstanceOf(LtsvFormatter.class);
    assertThat(LogEntryFormatterFactory.getFormatter(LogFormat.KEYVALUE))
        .isExactlyInstanceOf(KeyValueFormatter.class);
    assertThat(LogEntryFormatterFactory.getFormatter(LogFormat.SQUID))
        .isExactlyInstanceOf(SquidFormatter.class);
    assertThat(LogEntryFormatterFactory.getFormatter(LogFormat.HAPROXY))
        .isExactlyInstanceOf(HaproxyFormatter.class);
    assertThat(LogEntryFormatterFactory.getFormatter(LogFormat.W3C))
        .isExactlyInstanceOf(W3cFormatter.class);
  }
}
