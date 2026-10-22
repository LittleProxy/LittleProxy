package org.littleshoot.proxy.impl;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import java.net.SocketException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.spi.LocationAwareLogger;

final class ProxyConnectionLoggerTest {
  @ParameterizedTest
  @CsvSource({
    "example.test:443, example.test:443",
    "user:secret@example.test:8443/path?token=secret#fragment, example.test:8443",
    "[::1]:443, [::1]:443",
    "not a valid authority, unknown"
  })
  void resetLoggingIncludesSafeTargetAndPreservesThrowable(String target, String expected)
      throws Exception {
    TestConnection connection = new TestConnection(true, target);
    LocationAwareLogger logger = mock();
    when(logger.isInfoEnabled()).thenReturn(true);
    ProxyConnectionLogger connectionLogger = new ProxyConnectionLogger(connection, logger);
    SocketException reset = new SocketException("Connection reset");

    connectionLogger.info("An IOException occurred", reset);

    verify(logger)
        .log(
            null,
            ProxyConnectionLogger.class.getCanonicalName(),
            LocationAwareLogger.INFO_INT,
            prefix(connection, "upstream", expected) + "An IOException occurred",
            null,
            reset);
  }

  @Test
  void fallbackLoggerIncludesClientSideAndPreservesThrowable() {
    TestConnection connection = new TestConnection(false, null);
    Logger logger = mock();
    when(logger.isInfoEnabled()).thenReturn(true);
    SocketException reset = new SocketException("Connection reset");

    new ProxyConnectionLogger(connection, logger).info("Connection reset", reset);

    verify(logger)
        .info(
            eq(prefix(connection, "client", "unknown") + "Connection reset"),
            eq(new Object[] {reset}));
  }

  @Test
  void targetIsReadAtLogTimeRatherThanCachedFromTheFirstRequest() {
    TestConnection connection = new TestConnection(false, "first.test:80");
    LocationAwareLogger logger = mock();
    when(logger.isWarnEnabled()).thenReturn(true);
    ProxyConnectionLogger connectionLogger = new ProxyConnectionLogger(connection, logger);
    connection.target = "second.test:443";

    connectionLogger.warn("Connection reset");

    verify(logger)
        .log(
            null,
            ProxyConnectionLogger.class.getCanonicalName(),
            LocationAwareLogger.WARN_INT,
            prefix(connection, "client", "second.test:443") + "Connection reset",
            null,
            null);
  }

  @Test
  void multilineAndOverlongTargetsDoNotReachLogs() {
    TestConnection connection = new TestConnection(true, "example.test:443\r\nsecret");
    LocationAwareLogger logger = mock();
    when(logger.isInfoEnabled()).thenReturn(true);
    ProxyConnectionLogger connectionLogger = new ProxyConnectionLogger(connection, logger);

    connectionLogger.info("First reset");
    connection.target = "x".repeat(3000) + ":443";
    connectionLogger.info("Second reset");

    verify(logger)
        .log(
            null,
            ProxyConnectionLogger.class.getCanonicalName(),
            LocationAwareLogger.INFO_INT,
            prefix(connection, "upstream", "unknown") + "First reset",
            null,
            null);
    verify(logger)
        .log(
            null,
            ProxyConnectionLogger.class.getCanonicalName(),
            LocationAwareLogger.INFO_INT,
            prefix(connection, "upstream", "unknown") + "Second reset",
            null,
            null);
  }

  private static String prefix(TestConnection connection, String side, String target) {
    return "(AWAITING_INITIAL) id="
        + connection.getId()
        + " side="
        + side
        + " target="
        + target
        + ": ";
  }

  private static final class TestConnection extends ProxyConnection<HttpRequest> {
    @Nullable private String target;

    TestConnection(boolean upstream, @Nullable String target) {
      super(ConnectionState.AWAITING_INITIAL, mock(DefaultHttpProxyServer.class), upstream);
      this.target = target;
    }

    @Override
    @Nullable
    protected String getLogTarget() {
      return target;
    }

    @Override
    protected void readHAProxyMessage(HAProxyMessage message) {}

    @Override
    ConnectionState readHTTPInitial(HttpRequest request) {
      return ConnectionState.AWAITING_INITIAL;
    }

    @Override
    protected void readHTTPChunk(HttpContent content) {}

    @Override
    protected void readRaw(ByteBuf buffer) {}
  }
}
