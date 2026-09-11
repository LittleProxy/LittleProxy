package org.littleshoot.proxy.impl;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.ServerConnectionPoolType;

/** Configuration for the server connection pool. */
public class ServerConnectionPoolConfig {
  private boolean enabled = false;
  private ServerConnectionPoolType poolType = ServerConnectionPoolType.CONCURRENT_MAP;
  private int maxConnectionsPerHost =
      ConcurrentMapServerConnectionPool.DEFAULT_MAX_CONNECTIONS_PER_HOST;
  private int maxConnections = ConcurrentMapServerConnectionPool.DEFAULT_MAX_TOTAL_CONNECTIONS;
  @Nullable private Duration idleTimeout;
  private boolean poolSharedMitmConnections = false;
  private boolean poolPerRequestInMitm = false;

  public boolean isEnabled() {
    return enabled;
  }

  public ServerConnectionPoolConfig setEnabled(boolean enabled) {
    this.enabled = enabled;
    return this;
  }

  public ServerConnectionPoolType getPoolType() {
    return poolType;
  }

  public ServerConnectionPoolConfig setPoolType(ServerConnectionPoolType poolType) {
    this.poolType = requireNonNull(poolType, "poolType must not be null");
    return this;
  }

  public int getMaxConnectionsPerHost() {
    return maxConnectionsPerHost;
  }

  public ServerConnectionPoolConfig setMaxConnectionsPerHost(int maxConnectionsPerHost) {
    if (maxConnectionsPerHost <= 0) {
      throw new IllegalArgumentException(
          "maxConnectionsPerHost must be positive: " + maxConnectionsPerHost);
    }
    this.maxConnectionsPerHost = maxConnectionsPerHost;
    return this;
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  public ServerConnectionPoolConfig setMaxConnections(int maxConnections) {
    if (maxConnections <= 0) {
      throw new IllegalArgumentException("maxConnections must be positive: " + maxConnections);
    }
    this.maxConnections = maxConnections;
    return this;
  }

  @Nullable
  public Duration getIdleTimeout() {
    return idleTimeout;
  }

  public ServerConnectionPoolConfig setIdleTimeout(@Nullable Duration idleTimeout) {
    this.idleTimeout = idleTimeout;
    return this;
  }

  public boolean isPoolSharedMitmConnections() {
    return poolSharedMitmConnections;
  }

  public ServerConnectionPoolConfig setPoolSharedMitmConnections(
      boolean poolSharedMitmConnections) {
    this.poolSharedMitmConnections = poolSharedMitmConnections;
    return this;
  }

  public boolean isPoolPerRequestInMitm() {
    return poolPerRequestInMitm;
  }

  public ServerConnectionPoolConfig setPoolPerRequestInMitm(boolean poolPerRequestInMitm) {
    this.poolPerRequestInMitm = poolPerRequestInMitm;
    return this;
  }
}
