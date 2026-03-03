package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
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
   * @param clientConnection the client connection that needs the server connection
   * @param initialFilters the initial HTTP filters
   * @param initialHttpRequest the initial HTTP request
   * @return the ProxyToServerConnection, or null if creation failed or pool is exhausted
   */
  @Nullable ProxyToServerConnection getOrCreateConnection(
      String serverHostAndPort,
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
   */
  void registerPendingRequest(
      Channel channel, ClientToProxyConnection clientConnection, HttpRequest request);

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
   * Removes a connection from the pool when it's disconnected.
   *
   * @param serverHostAndPort the server host and port key
   * @param connection the connection being removed
   */
  void removeConnection(String serverHostAndPort, ProxyToServerConnection connection);

  /** Closes all connections in the pool. */
  void closeAll();

  /** Returns the maximum number of connections allowed per host. */
  int getMaxConnectionsPerHost();

  /** Returns the maximum total number of connections allowed in the pool. */
  int getMaxConnections();

  /** Tracks a pending request and its associated client connection for HTTP pipelining. */
  class PendingRequest {
    private final ClientToProxyConnection clientConnection;
    private final HttpRequest request;
    private final long timestamp;

    public PendingRequest(ClientToProxyConnection clientConnection, HttpRequest request) {
      this.clientConnection = clientConnection;
      this.request = request;
      this.timestamp = System.currentTimeMillis();
    }

    public ClientToProxyConnection getClientConnection() {
      return clientConnection;
    }

    public HttpRequest getRequest() {
      return request;
    }

    public long getTimestamp() {
      return timestamp;
    }
  }
}
