package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.HttpFilters;

/**
 * Interface for pooling ProxyToServerConnection instances.
 *
 * <p>This interface allows swapping different pooling implementations:
 *
 * <ul>
 *   <li>{@link ConcurrentMapServerConnectionPool} - Simple ConcurrentHashMap-based pool
 *   <li>{@link CommonsPoolServerConnectionPool} - Apache Commons Pool 2 based
 *   <li>{@link StormpotServerConnectionPool} - Stormpot-based for high performance
 * </ul>
 */
public interface ServerConnectionPool {

  /**
   * Gets a connection for the given host and port, or creates one if it doesn't exist.
   *
   * @param serverHostAndPort the server host and port key
   * @param chainedProxyAddress the address of the resolved chained proxy, used to segregate
   *     connections by upstream route (null for direct connections)
   * @param clientConnection the client connection that needs the server connection
   * @param initialFilters the initial HTTP filters
   * @param initialHttpRequest the initial HTTP request
   * @return the ProxyToServerConnection, or null if creation failed or pool is exhausted
   */
  @Nullable ProxyToServerConnection getOrCreateConnection(
      String serverHostAndPort,
      @Nullable InetSocketAddress chainedProxyAddress,
      ClientToProxyConnection clientConnection,
      HttpFilters initialFilters,
      HttpRequest initialHttpRequest);

  /**
   * Releases a connection back to the pool after a request is complete.
   *
   * @param connection the connection to release
   */
  void releaseConnection(ProxyToServerConnection connection);

  /**
   * Registers a pending request for HTTP pipelining support.
   *
   * @param channel the server channel
   * @param clientConnection the client connection that made the request
   * @param request the HTTP request
   * @param filters the filters active for this request
   */
  void registerPendingRequest(
      Channel channel,
      ClientToProxyConnection clientConnection,
      HttpRequest request,
      HttpFilters filters);

  /**
   * Gets and removes the oldest pending request for the given channel (FIFO order for pipelining).
   *
   * @param channel the server channel
   * @return the oldest pending request, or null if none found
   */
  @Nullable PendingRequest removePendingRequest(Channel channel);

  /**
   * Gets the oldest pending request without removing it.
   *
   * @param channel the server channel
   * @return the oldest pending request, or null if none found
   */
  @Nullable PendingRequest peekPendingRequest(Channel channel);

  /**
   * Drains and removes all pending requests for the given channel.
   *
   * @param channel the server channel
   */
  void drainPendingRequests(Channel channel);

  /**
   * Removes a connection from the pool when it's disconnected.
   *
   * @param connection the connection being removed
   */
  void removeConnection(ProxyToServerConnection connection);

  /** Closes all connections in the pool. */
  void closeAll();

  /** Returns the maximum number of connections allowed per host. */
  int getMaxConnectionsPerHost();

  /** Returns the maximum total number of connections allowed in the pool. */
  int getMaxConnections();

  /**
   * Sets the idle timeout for connections. Connections idle for longer than this duration will be
   * evicted.
   *
   * @param idleTimeout the idle timeout duration, or null to disable
   */
  void setIdleTimeout(@Nullable Duration idleTimeout);

  /** Returns the configured idle timeout, or null if not set. */
  @Nullable Duration getIdleTimeout();

  /**
   * Enables or disables connection validation. When enabled, connections are validated before being
   * borrowed from the pool to ensure they are still functional.
   *
   * @param validationEnabled true to enable validation, false to disable
   */
  void setConnectionValidationEnabled(boolean validationEnabled);

  /** Returns true if connection validation is enabled. */
  boolean isConnectionValidationEnabled();

  /**
   * Returns current pool metrics.
   *
   * @return PoolMetrics with current statistics
   */
  PoolMetrics getMetrics();

  default String computePoolKey(
      String serverHostAndPort, @Nullable InetSocketAddress chainedProxyAddress) {
    if (chainedProxyAddress == null) {
      return serverHostAndPort + ":direct";
    }
    if (chainedProxyAddress.getAddress() == null) {
      // Unresolved address - use hostname string instead
      return serverHostAndPort
          + ":"
          + chainedProxyAddress.getHostString()
          + ":"
          + chainedProxyAddress.getPort();
    }
    return serverHostAndPort
        + ":"
        + chainedProxyAddress.getAddress().getHostAddress()
        + ":"
        + chainedProxyAddress.getPort();
  }
}
