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

/** ConcurrentHashMap-based implementation of {@link ServerConnectionPool}. */
public class ConcurrentMapServerConnectionPool implements ServerConnectionPool {
  private static final Logger LOG =
      LoggerFactory.getLogger(ConcurrentMapServerConnectionPool.class);

  /** Default maximum number of pooled connections per host:port. */
  static final int DEFAULT_MAX_CONNECTIONS_PER_HOST = 10;

  /** Default maximum total connections in the pool. */
  static final int DEFAULT_MAX_TOTAL_CONNECTIONS = 200;

  /** The map of server host:port to set of ProxyToServerConnection. */
  private final ConcurrentMap<String, ConcurrentMap<ProxyToServerConnection, Boolean>>
      connectionsByHostAndPort = new ConcurrentHashMap<>();

  /** Available connections per host:port. */
  private final ConcurrentMap<String, Queue<ProxyToServerConnection>>
      availableConnectionsByHostAndPort = new ConcurrentHashMap<>();

  /** Track the number of connections per host:port. */
  private final ConcurrentMap<String, AtomicInteger> connectionCountByHostAndPort =
      new ConcurrentHashMap<>();

  /** Track pending requests by channel to support HTTP pipelining. */
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

  public ConcurrentMapServerConnectionPool(
      DefaultHttpProxyServer proxyServer,
      io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler) {
    this(
        proxyServer,
        globalTrafficShapingHandler,
        DEFAULT_MAX_CONNECTIONS_PER_HOST,
        DEFAULT_MAX_TOTAL_CONNECTIONS);
  }

  public ConcurrentMapServerConnectionPool(
      DefaultHttpProxyServer proxyServer,
      io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler,
      int maxConnectionsPerHost) {
    this(
        proxyServer,
        globalTrafficShapingHandler,
        maxConnectionsPerHost,
        DEFAULT_MAX_TOTAL_CONNECTIONS);
  }

  public ConcurrentMapServerConnectionPool(
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

  @Override
  @Nullable
  public ProxyToServerConnection getOrCreateConnection(
      String serverHostAndPort,
      ClientToProxyConnection clientConnection,
      HttpFilters initialFilters,
      HttpRequest initialHttpRequest) {
    ProxyToServerConnection available = borrowAvailableConnection(serverHostAndPort);
    if (available != null) {
      return available;
    }

    int currentHostCount =
        connectionCountByHostAndPort
            .computeIfAbsent(serverHostAndPort, k -> new AtomicInteger(0))
            .get();

    if (currentHostCount >= maxConnectionsPerHost) {
      LOG.warn(
          "Per-host connection limit reached for {}: {} connections, max is {}",
          serverHostAndPort,
          currentHostCount,
          maxConnectionsPerHost);
      return null;
    }

    if (totalConnectionsCreated.get() >= maxConnections) {
      LOG.warn(
          "Pool exhausted: {} connections created, max is {}",
          totalConnectionsCreated.get(),
          maxConnections);
      return null;
    }

    synchronized (this) {
      ProxyToServerConnection existingAvailable = borrowAvailableConnection(serverHostAndPort);
      if (existingAvailable != null) {
        return existingAvailable;
      }

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
        return null;
      }

      if (totalConnectionsCreated.get() >= maxConnections) {
        LOG.warn(
            "Pool exhausted after sync: {} connections created, max is {}",
            totalConnectionsCreated.get(),
            maxConnections);
        return null;
      }

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

  @Override
  public void releaseConnection(ProxyToServerConnection connection) {
    if (connection == null) {
      return;
    }
    String serverHostAndPort = connection.getServerHostAndPort();
    ConcurrentMap<ProxyToServerConnection, Boolean> connections =
        connectionsByHostAndPort.get(serverHostAndPort);
    if (connections == null || !connections.containsKey(connection)) {
      return;
    }
    if (!connection.isConnected()) {
      removeConnection(serverHostAndPort, connection);
      return;
    }
    availableConnectionsByHostAndPort
        .computeIfAbsent(serverHostAndPort, k -> new ConcurrentLinkedQueue<>())
        .offer(connection);
  }

  @Override
  public void registerPendingRequest(
      Channel channel, ClientToProxyConnection clientConnection, HttpRequest request) {
    pendingRequestsByChannel
        .computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>())
        .add(new PendingRequest(clientConnection, request));
  }

  @Override
  @Nullable
  public PendingRequest removePendingRequest(Channel channel) {
    Queue<PendingRequest> queue = pendingRequestsByChannel.get(channel);
    if (queue == null || queue.isEmpty()) {
      return null;
    }
    PendingRequest request = queue.poll();
    if (queue.isEmpty()) {
      pendingRequestsByChannel.remove(channel);
    }
    return request;
  }

  @Override
  @Nullable
  public PendingRequest peekPendingRequest(Channel channel) {
    Queue<PendingRequest> queue = pendingRequestsByChannel.get(channel);
    if (queue == null || queue.isEmpty()) {
      return null;
    }
    return queue.peek();
  }

  @Override
  public void removeConnection(String serverHostAndPort, ProxyToServerConnection connection) {
    ConcurrentMap<ProxyToServerConnection, Boolean> connections =
        connectionsByHostAndPort.get(serverHostAndPort);
    if (connections != null) {
      if (connections.remove(connection) != null) {
        AtomicInteger count = connectionCountByHostAndPort.get(serverHostAndPort);
        if (count != null) {
          int newCount = count.decrementAndGet();
          if (newCount <= 0) {
            connectionCountByHostAndPort.remove(serverHostAndPort);
            connectionsByHostAndPort.remove(serverHostAndPort);
            availableConnectionsByHostAndPort.remove(serverHostAndPort);
          }
        }
        totalConnectionsCreated.decrementAndGet();
      }
    }
    Queue<ProxyToServerConnection> available =
        availableConnectionsByHostAndPort.get(serverHostAndPort);
    if (available != null) {
      available.remove(connection);
    }
  }

  @Override
  public void closeAll() {
    for (ConcurrentMap<ProxyToServerConnection, Boolean> connections :
        connectionsByHostAndPort.values()) {
      for (ProxyToServerConnection connection : connections.keySet()) {
        connection.close();
      }
    }
    connectionsByHostAndPort.clear();
    availableConnectionsByHostAndPort.clear();
    connectionCountByHostAndPort.clear();
    pendingRequestsByChannel.clear();
    totalConnectionsCreated.set(0);
  }

  @Override
  public int getMaxConnectionsPerHost() {
    return maxConnectionsPerHost;
  }

  @Override
  public int getMaxConnections() {
    return maxConnections;
  }

  @Nullable
  private ProxyToServerConnection borrowAvailableConnection(String serverHostAndPort) {
    Queue<ProxyToServerConnection> available =
        availableConnectionsByHostAndPort.get(serverHostAndPort);
    if (available == null || available.isEmpty()) {
      return null;
    }
    while (true) {
      ProxyToServerConnection conn = available.poll();
      if (conn == null) {
        return null;
      }
      if (conn.isConnected() && conn.isAvailableForNewRequest()) {
        return conn;
      }
      if (!conn.isConnected()) {
        removeConnection(serverHostAndPort, conn);
      }
    }
  }
}
