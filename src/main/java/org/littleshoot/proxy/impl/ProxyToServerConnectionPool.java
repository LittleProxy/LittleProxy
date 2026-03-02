package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.HttpFilters;

/**
 * A shared pool of ProxyToServerConnection instances that can be reused across all
 * ClientToProxyConnection instances. This addresses the connection explosion issue described in
 * GitHub issue #83.
 *
 * <p>Instead of each ClientToProxyConnection maintaining its own pool of server connections, all
 * client connections share a common pool. This allows connections to the same server to be reused
 * across different client connections.
 *
 * <p>This pool supports HTTP pipelining - multiple requests can be pending on a single connection,
 * and responses are matched to the correct client based on request order.
 */
public class ProxyToServerConnectionPool {
  /** Default maximum number of pooled connections per host:port. */
  private static final int DEFAULT_MAX_CONNECTIONS_PER_HOST = 200;

  /** The map of server host:port to ProxyToServerConnection. */
  private final ConcurrentMap<String, ProxyToServerConnection> connectionsByHostAndPort =
      new ConcurrentHashMap<>();

  /**
   * Track pending requests by channel to support HTTP pipelining. Each channel can have multiple
   * pending requests in flight.
   */
  private final ConcurrentMap<Channel, Queue<PendingRequest>> pendingRequestsByChannel =
      new ConcurrentHashMap<>();

  /** Track the number of connections created from this pool to prevent exhaustion. */
  private final AtomicInteger totalConnectionsCreated = new AtomicInteger(0);

  /** Maximum number of connections allowed in the pool. */
  private final int maxConnections;

  /** The proxy server that owns this pool. */
  private final DefaultHttpProxyServer proxyServer;

  /** The global traffic shaping handler. */
  private final io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler;

  public ProxyToServerConnectionPool(
      DefaultHttpProxyServer proxyServer,
      io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler) {
    this(proxyServer, globalTrafficShapingHandler, DEFAULT_MAX_CONNECTIONS_PER_HOST);
  }

  public ProxyToServerConnectionPool(
      DefaultHttpProxyServer proxyServer,
      io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler,
      int maxConnections) {
    this.proxyServer = proxyServer;
    this.globalTrafficShapingHandler = globalTrafficShapingHandler;
    this.maxConnections = maxConnections > 0 ? maxConnections : DEFAULT_MAX_CONNECTIONS_PER_HOST;
  }

  /**
   * Gets a connection for the given host and port, or creates one if it doesn't exist.
   *
   * @param serverHostAndPort the server host and port key
   * @param clientConnection the client connection that needs the server connection
   * @param initialFilters the initial HTTP filters
   * @param initialHttpRequest the initial HTTP request
   * @return the ProxyToServerConnection, or null if creation failed or pool is exhausted
   */
  @Nullable
  public ProxyToServerConnection getOrCreateConnection(
      String serverHostAndPort,
      ClientToProxyConnection clientConnection,
      HttpFilters initialFilters,
      HttpRequest initialHttpRequest) {
    // Try to get existing connection first
    ProxyToServerConnection existingConnection = connectionsByHostAndPort.get(serverHostAndPort);

    if (existingConnection != null && existingConnection.isConnected()) {
      // Connection exists and is connected - check if it's available for reuse
      if (existingConnection.isAvailableForNewRequest()) {
        return existingConnection;
      }
    }

    // Check if pool is exhausted before creating new connection
    if (totalConnectionsCreated.get() >= maxConnections) {
      org.slf4j.LoggerFactory.getLogger(ProxyToServerConnectionPool.class)
          .warn(
              "Pool exhausted: {} connections created, max is {}",
              totalConnectionsCreated.get(),
              maxConnections);
      return null;
    }

    // Need to create a new connection
    synchronized (this) {
      // Double-check after acquiring lock
      existingConnection = connectionsByHostAndPort.get(serverHostAndPort);
      if (existingConnection != null && existingConnection.isConnected()) {
        if (existingConnection.isAvailableForNewRequest()) {
          return existingConnection;
        }
      }

      // Check again after sync block
      if (totalConnectionsCreated.get() >= maxConnections) {
        org.slf4j.LoggerFactory.getLogger(ProxyToServerConnectionPool.class)
            .warn(
                "Pool exhausted after sync: {} connections created, max is {}",
                totalConnectionsCreated.get(),
                maxConnections);
        return null;
      }

      // Create new connection
      try {
        ProxyToServerConnection newConnection =
            ProxyToServerConnection.createForPool(
                proxyServer,
                this,
                clientConnection,
                serverHostAndPort,
                initialFilters,
                initialHttpRequest,
                globalTrafficShapingHandler);

        if (newConnection != null) {
          totalConnectionsCreated.incrementAndGet();
          connectionsByHostAndPort.put(serverHostAndPort, newConnection);
          return newConnection;
        }
      } catch (java.net.UnknownHostException e) {
        org.slf4j.LoggerFactory.getLogger(ProxyToServerConnectionPool.class)
            .warn("Failed to resolve host for {}", serverHostAndPort, e);
      }
    }

    return null;
  }

  /**
   * Registers a pending request for HTTP pipelining support.
   *
   * @param channel the server channel
   * @param clientConnection the client connection that made the request
   * @param request the HTTP request
   */
  public void registerPendingRequest(
      Channel channel, ClientToProxyConnection clientConnection, HttpRequest request) {
    pendingRequestsByChannel
        .computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>())
        .add(new PendingRequest(clientConnection, request));
  }

  /**
   * Gets and removes the oldest pending request for the given channel (FIFO order for pipelining).
   *
   * @param channel the server channel
   * @return the oldest pending request, or null if none found
   */
  @Nullable
  public PendingRequest removePendingRequest(Channel channel) {
    Queue<PendingRequest> queue = pendingRequestsByChannel.get(channel);
    if (queue == null || queue.isEmpty()) {
      return null;
    }
    PendingRequest request = queue.poll();
    // Clean up empty queues
    if (queue.isEmpty()) {
      pendingRequestsByChannel.remove(channel);
    }
    return request;
  }

  /**
   * Gets the oldest pending request without removing it.
   *
   * @param channel the server channel
   * @return the oldest pending request, or null if none found
   */
  @Nullable
  public PendingRequest peekPendingRequest(Channel channel) {
    Queue<PendingRequest> queue = pendingRequestsByChannel.get(channel);
    if (queue == null || queue.isEmpty()) {
      return null;
    }
    return queue.peek();
  }

  /**
   * Removes a connection from the pool when it's disconnected.
   *
   * @param serverHostAndPort the server host and port key
   * @param connection the connection being removed
   */
  public void removeConnection(String serverHostAndPort, ProxyToServerConnection connection) {
    connectionsByHostAndPort.computeIfPresent(
        serverHostAndPort,
        (key, existing) -> {
          if (existing == connection) {
            totalConnectionsCreated.decrementAndGet();
            return null;
          }
          return existing;
        });
  }

  /** Closes all connections in the pool. */
  public void closeAll() {
    for (ProxyToServerConnection connection : connectionsByHostAndPort.values()) {
      connection.close();
    }
    connectionsByHostAndPort.clear();
    pendingRequestsByChannel.clear();
    totalConnectionsCreated.set(0);
  }

  /** Tracks a pending request and its associated client connection for HTTP pipelining. */
  public static class PendingRequest {
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
