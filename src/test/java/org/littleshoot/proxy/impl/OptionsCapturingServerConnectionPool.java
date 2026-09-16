package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.HttpFilters;

/**
 * Test-only {@link ServerConnectionPool} registered through the {@link java.util.ServiceLoader} in
 * test resources. Records the options map it was initialized with so tests can assert that options
 * from a properties file or the bootstrap reach the pool context. Each instance keeps its own
 * capture, so concurrent or overlapping pool creations across tests cannot interfere.
 */
public class OptionsCapturingServerConnectionPool implements ServerConnectionPool {

  static final String NAME = "OPTIONS_CAPTURE_POOL";

  private final AtomicReference<Map<String, Object>> capturedOptions = new AtomicReference<>();

  public OptionsCapturingServerConnectionPool() {}

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void initialize(ServerConnectionPoolContext context) {
    capturedOptions.set(context.getOptions());
  }

  /** Returns the options map this instance was initialized with, or {@code null} if not yet. */
  @Nullable Map<String, Object> getCapturedOptions() {
    return capturedOptions.get();
  }

  @Override
  public @Nullable ProxyToServerConnection getOrCreateConnection(
      String serverHostAndPort,
      @Nullable InetSocketAddress chainedProxyAddress,
      ClientToProxyConnection clientConnection,
      HttpFilters initialFilters,
      HttpRequest initialHttpRequest) {
    return null;
  }

  @Override
  public void releaseConnection(ProxyToServerConnection connection) {}

  @Override
  public void registerPendingRequest(
      Channel channel,
      ClientToProxyConnection clientConnection,
      HttpRequest request,
      HttpFilters filters) {}

  @Override
  public @Nullable PendingRequest removePendingRequest(Channel channel) {
    return null;
  }

  @Override
  public @Nullable PendingRequest peekPendingRequest(Channel channel) {
    return null;
  }

  @Override
  public void drainPendingRequests(Channel channel) {}

  @Override
  public void removeConnection(ProxyToServerConnection connection) {}

  @Override
  public void closeAll() {}

  @Override
  public int getMaxConnectionsPerHost() {
    return -1;
  }

  @Override
  public int getMaxConnections() {
    return -1;
  }

  @Override
  public void setIdleTimeout(@Nullable Duration idleTimeout) {}

  @Override
  public @Nullable Duration getIdleTimeout() {
    return null;
  }

  @Override
  public void setConnectionValidationEnabled(boolean validationEnabled) {}

  @Override
  public boolean isConnectionValidationEnabled() {
    return false;
  }

  @Override
  public PoolMetrics getMetrics() {
    return new PoolMetrics(0, 0, 0, 0, 0, 0, 0);
  }
}
