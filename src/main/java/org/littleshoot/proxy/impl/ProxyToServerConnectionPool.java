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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>Per-host connection limits can be configured to allow multiple connections to the same
 * host:port for high concurrency scenarios.
 */
public class ProxyToServerConnectionPool {
  private static final Logger LOG = LoggerFactory.getLogger(ProxyToServerConnectionPool.class);

  /** Default maximum number of pooled connections per host:port. */
  private static final int DEFAULT_MAX_CONNECTIONS_PER_HOST = 10;

  /** Default maximum total connections in the pool. */
  private static final int DEFAULT_MAX_TOTAL_CONNECTIONS = 200;

  /** The map of server host:port to set of ProxyToServerConnection. */
  private final ConcurrentMap<String, ConcurrentMap<ProxyToServerConnection, Boolean>>
      connectionsByHostAndPort = new ConcurrentHashMap<>();

  /**
   * Track the number of connections per host:port. Uses AtomicInteger to allow concurrent access.
   */
  private final ConcurrentMap<String, AtomicInteger> connectionCountByHostAndPort =
      new ConcurrentHashMap<>();

  /**
   * Track pending requests by channel to support HTTP pipelining. Each channel can have multiple
   * pending requests in flight.
   */
  private final ConcurrentMap<Channel, Queue<PendingRequest>> pendingRequestsByChannel =
      new ConcurrentHashMap<>();

  /** Track the number of connections created from this pool to prevent exhaustion. */
  private final AtomicInteger totalConnectionsCreated = new AtomicInteger(0);

  /** Maximum number of connections allowed per host:port. */
  private final int maxConnectionsPerHost;

  /** Maximum total connections allowed in the pool. */
  private final int maxConnections;

  /** The proxy server that owns this pool. */
  private final DefaultHttpProxyServer proxyServer;

  /** The global traffic shaping handler. */
  private final io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler;

  public ProxyToServerConnectionPool(
      DefaultHttpProxyServer proxyServer,
      io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler) {
    this(
        proxyServer,
        globalTrafficShapingHandler,
        DEFAULT_MAX_CONNECTIONS_PER_HOST,
        DEFAULT_MAX_TOTAL_CONNECTIONS);
  }

  public ProxyToServerConnectionPool(
      DefaultHttpProxyServer proxyServer,
      io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler,
      int maxConnectionsPerHost) {
    this(
        proxyServer,
        globalTrafficShapingHandler,
        maxConnectionsPerHost,
        DEFAULT_MAX_TOTAL_CONNECTIONS);
  }

  public ProxyToServerConnectionPool(
      DefaultHttpProxyServer proxyServer,
      io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler,
      int maxConnectionsPerHost,
      int maxConnections) {
    this.proxyServer = proxyServer;
    this.globalTrafficShapingHandler = globalTrafficShapingHandler;
    this.maxConnectionsPerHost =
        maxConnectionsPerHost > 0 ? maxConnectionsPerHost : DEFAULT_MAX_CONNECTIONS_PER_HOST;
    this.maxConnections = maxConnections > 0 ? maxConnections : DEFAULT_MAX_TOTAL_CONNECTIONS;
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

    // Get current count for this host
    int currentHostCount =
        connectionCountByHostAndPort
            .computeIfAbsent(serverHostAndPort, k -> new AtomicInteger(0))
            .get();

    // Check per-host limit first
    if (currentHostCount >= maxConnectionsPerHost) {
      LOG.warn(
          "Per-host connection limit reached for {}: {} connections, max is {}",
          serverHostAndPort,
          currentHostCount,
          maxConnectionsPerHost);
      // Try to find an existing available connection
      ProxyToServerConnection available = findAvailableConnection(serverHostAndPort);
      if (available != null) {
        return available;
      }
      return null;
    }

    // Check if pool is exhausted before creating new connection
    if (totalConnectionsCreated.get() >= maxConnections) {
      LOG.warn(
          "Pool exhausted: {} connections created, max is {}",
          totalConnectionsCreated.get(),
          maxConnections);
      // Try to find an existing available connection
      ProxyToServerConnection available = findAvailableConnection(serverHostAndPort);
      if (available != null) {
        return available;
      }
      return null;
    }

    // Need to create a new connection - use double-checked locking
    synchronized (this) {
      // Re-check per-host limit after acquiring lock
      int hostCount =
          connectionCountByHostAndPort
              .computeIfAbsent(serverHostAndPort, k -> new AtomicInteger(0))
              .get();

      if (hostCount >= maxConnectionsPerHost) {
        LOG.warn(
            "Per-host limit reached after sync for {}: {} connections, max is {}",
            serverHostAndPort,
            hostCount,
            maxConnectionsPerHost);
        return findAvailableConnection(serverHostAndPort);
      }

      // Re-check global limit
      if (totalConnectionsCreated.get() >= maxConnections) {
        LOG.warn(
            "Pool exhausted after sync: {} connections created, max is {}",
            totalConnectionsCreated.get(),
            maxConnections);
        return findAvailableConnection(serverHostAndPort);
      }

      // Try to find an available connection before creating a new one
      ProxyToServerConnection existingAvailable = findAvailableConnection(serverHostAndPort);
      if (existingAvailable != null) {
        return existingAvailable;
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
          // Add to tracking
          connectionsByHostAndPort
              .computeIfAbsent(serverHostAndPort, k -> new ConcurrentHashMap<>())
              .put(newConnection, Boolean.TRUE);
          connectionCountByHostAndPort
              .computeIfAbsent(serverHostAndPort, k -> new AtomicInteger(0))
              .incrementAndGet();
          totalConnectionsCreated.incrementAndGet();
          return newConnection;
        }
      } catch (java.net.UnknownHostException e) {
        LOG.warn("Failed to resolve host for {}", serverHostAndPort, e);
      }
    }

    return null;
  }

  /** Finds an available (connected and not processing a request) connection for the given host. */
  @Nullable
  private ProxyToServerConnection findAvailableConnection(String serverHostAndPort) {
    ConcurrentMap<ProxyToServerConnection, Boolean> connections =
        connectionsByHostAndPort.get(serverHostAndPort);
    if (connections == null || connections.isEmpty()) {
      return null;
    }
    for (ProxyToServerConnection conn : connections.keySet()) {
      if (conn.isConnected() && conn.isAvailableForNewRequest()) {
        return conn;
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
    ConcurrentMap<ProxyToServerConnection, Boolean> connections =
        connectionsByHostAndPort.get(serverHostAndPort);
    if (connections != null) {
      if (connections.remove(connection) != null) {
        // Decrement the count
        AtomicInteger count = connectionCountByHostAndPort.get(serverHostAndPort);
        if (count != null) {
          int newCount = count.decrementAndGet();
          if (newCount <= 0) {
            // Clean up empty entries
            connectionCountByHostAndPort.remove(serverHostAndPort);
            connectionsByHostAndPort.remove(serverHostAndPort);
          }
        }
        totalConnectionsCreated.decrementAndGet();
      }
    }
  }

  /** Closes all connections in the pool. */
  public void closeAll() {
    for (ConcurrentMap<ProxyToServerConnection, Boolean> connections :
        connectionsByHostAndPort.values()) {
      for (ProxyToServerConnection connection : connections.keySet()) {
        connection.close();
      }
    }
    connectionsByHostAndPort.clear();
    connectionCountByHostAndPort.clear();
    pendingRequestsByChannel.clear();
    totalConnectionsCreated.set(0);
  }

  /** Returns the maximum number of connections allowed per host. */
  public int getMaxConnectionsPerHost() {
    return maxConnectionsPerHost;
  }

  /** Returns the maximum total number of connections allowed in the pool. */
  public int getMaxConnections() {
    return maxConnections;
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
