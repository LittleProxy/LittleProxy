package org.littleshoot.proxy.impl;

import java.time.Duration;
import org.littleshoot.proxy.ServerConnectionPoolType;

/** Configuration for the server connection pool. */
public class ServerConnectionPoolConfig {
  private boolean enabled = false;
  private ServerConnectionPoolType poolType = ServerConnectionPoolType.CONCURRENT_MAP;
  private int maxConnectionsPerHost =
      ConcurrentMapServerConnectionPool.DEFAULT_MAX_CONNECTIONS_PER_HOST;
  private int maxConnections = ConcurrentMapServerConnectionPool.DEFAULT_MAX_TOTAL_CONNECTIONS;
  private Duration idleTimeout;

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
    this.poolType = poolType != null ? poolType : ServerConnectionPoolType.CONCURRENT_MAP;
    return this;
  }

  public int getMaxConnectionsPerHost() {
    return maxConnectionsPerHost;
  }

  public ServerConnectionPoolConfig setMaxConnectionsPerHost(int maxConnectionsPerHost) {
    this.maxConnectionsPerHost =
        maxConnectionsPerHost > 0 ? maxConnectionsPerHost : this.maxConnectionsPerHost;
    return this;
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  public ServerConnectionPoolConfig setMaxConnections(int maxConnections) {
    this.maxConnections = maxConnections > 0 ? maxConnections : this.maxConnections;
    return this;
  }

  public Duration getIdleTimeout() {
    return idleTimeout;
  }

  public ServerConnectionPoolConfig setIdleTimeout(Duration idleTimeout) {
    this.idleTimeout = idleTimeout;
    return this;
  }
}
