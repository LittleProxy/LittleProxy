package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.HttpFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConcurrentMapServerConnectionPool implements ServerConnectionPool {
  private static final Logger LOG =
      LoggerFactory.getLogger(ConcurrentMapServerConnectionPool.class);

  static final int DEFAULT_MAX_CONNECTIONS_PER_HOST = 10;
  static final int DEFAULT_MAX_TOTAL_CONNECTIONS = 200;

  private final ConcurrentMap<String, ConcurrentMap<ProxyToServerConnection, Boolean>>
      connectionsByHostAndPort = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Queue<PooledConnection>> availableConnectionsByHostAndPort =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicInteger> connectionCountByHostAndPort =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<Channel, Queue<PendingRequest>> pendingRequestsByChannel =
      new ConcurrentHashMap<>();
  private final AtomicInteger totalConnectionsCreated = new AtomicInteger(0);

  @Nullable private volatile Duration idleTimeout;
  private volatile boolean connectionValidationEnabled = false;
  private final ScheduledExecutorService evictionScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "connection-pool-eviction");
            t.setDaemon(true);
            return t;
          });
  @Nullable private volatile ScheduledFuture<?> evictionTask;

  private final int maxConnectionsPerHost;
  private final int maxConnections;
  private final DefaultHttpProxyServer proxyServer;
  private final io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler;

  private final java.util.concurrent.atomic.AtomicLong borrowCount =
      new java.util.concurrent.atomic.AtomicLong(0);
  private final java.util.concurrent.atomic.AtomicLong returnCount =
      new java.util.concurrent.atomic.AtomicLong(0);
  private final java.util.concurrent.atomic.AtomicLong evictionCount =
      new java.util.concurrent.atomic.AtomicLong(0);
  private final java.util.concurrent.atomic.AtomicLong validationFailureCount =
      new java.util.concurrent.atomic.AtomicLong(0);

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
      borrowCount.incrementAndGet();
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
          borrowCount.incrementAndGet();
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
    returnCount.incrementAndGet();
    availableConnectionsByHostAndPort
        .computeIfAbsent(serverHostAndPort, k -> new ConcurrentLinkedQueue<>())
        .add(new PooledConnection(connection, System.currentTimeMillis()));
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
    Queue<PooledConnection> available =
        (Queue<PooledConnection>) availableConnectionsByHostAndPort.get(serverHostAndPort);
    if (available != null) {
      available.removeIf(p -> p.connection == connection);
    }
  }

  @Override
  public void closeAll() {
    stopEvictionTask();
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

  @Override
  public void setIdleTimeout(@Nullable Duration idleTimeout) {
    this.idleTimeout = idleTimeout;
    if (idleTimeout != null && idleTimeout.toMillis() > 0) {
      startEvictionTask();
    } else {
      stopEvictionTask();
    }
  }

  @Override
  @Nullable
  public Duration getIdleTimeout() {
    return idleTimeout;
  }

  @Override
  public void setConnectionValidationEnabled(boolean validationEnabled) {
    this.connectionValidationEnabled = validationEnabled;
    LOG.info("Connection validation enabled: {}", validationEnabled);
  }

  @Override
  public boolean isConnectionValidationEnabled() {
    return connectionValidationEnabled;
  }

  @Override
  public PoolMetrics getMetrics() {
    int total = totalConnectionsCreated.get();
    int idle = availableConnectionsByHostAndPort.values().stream().mapToInt(q -> q.size()).sum();
    return new PoolMetrics(
        total,
        total - idle,
        idle,
        borrowCount.get(),
        returnCount.get(),
        evictionCount.get(),
        validationFailureCount.get());
  }

  private void startEvictionTask() {
    if (evictionTask != null && !evictionTask.isCancelled()) {
      return;
    }
    long intervalMillis = idleTimeout != null ? idleTimeout.toMillis() / 2 : 30_000;
    evictionTask =
        evictionScheduler.scheduleAtFixedRate(
            this::evictIdleConnections, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    LOG.info("Started idle connection eviction task with interval {}ms", intervalMillis);
  }

  private void stopEvictionTask() {
    if (evictionTask != null) {
      evictionTask.cancel(false);
      evictionTask = null;
      LOG.info("Stopped idle connection eviction task");
    }
  }

  private void evictIdleConnections() {
    if (idleTimeout == null || idleTimeout.toMillis() <= 0) {
      return;
    }
    long now = System.currentTimeMillis();
    long idleThreshold = now - idleTimeout.toMillis();
    int evicted = 0;

    for (String serverHostAndPort : availableConnectionsByHostAndPort.keySet()) {
      Queue<PooledConnection> queue = availableConnectionsByHostAndPort.get(serverHostAndPort);
      if (queue == null) {
        continue;
      }
      Queue<PooledConnection> toRemove = new ConcurrentLinkedQueue<>();
      for (PooledConnection pooled : queue) {
        if (pooled.releasedAt < idleThreshold) {
          toRemove.add(pooled);
          evicted++;
        }
      }
      for (PooledConnection pooled : toRemove) {
        queue.remove(pooled);
        removeConnection(serverHostAndPort, pooled.connection);
        pooled.connection.close();
      }
    }
    if (evicted > 0) {
      evictionCount.addAndGet(evicted);
      LOG.debug("Evicted {} idle connections", evicted);
    }
  }

  @Nullable
  private ProxyToServerConnection borrowAvailableConnection(String serverHostAndPort) {
    Queue<PooledConnection> queue = availableConnectionsByHostAndPort.get(serverHostAndPort);
    if (queue == null || queue.isEmpty()) {
      return null;
    }
    while (true) {
      PooledConnection pooled = queue.poll();
      if (pooled == null) {
        return null;
      }
      if (!pooled.connection.isConnected()) {
        removeConnection(serverHostAndPort, pooled.connection);
        continue;
      }
      if (connectionValidationEnabled && !isConnectionValid(pooled.connection)) {
        validationFailureCount.incrementAndGet();
        removeConnection(serverHostAndPort, pooled.connection);
        pooled.connection.close();
        LOG.debug("Connection validation failed, removing connection to {}", serverHostAndPort);
        continue;
      }
      if (pooled.connection.isAvailableForNewRequest()) {
        return pooled.connection;
      }
    }
  }

  private boolean isConnectionValid(ProxyToServerConnection connection) {
    return connection.isConnected() && connection.isAvailableForNewRequest();
  }

  private static class PooledConnection {
    final ProxyToServerConnection connection;
    final long releasedAt;

    PooledConnection(ProxyToServerConnection connection, long releasedAt) {
      this.connection = connection;
      this.releasedAt = releasedAt;
    }
  }
}
