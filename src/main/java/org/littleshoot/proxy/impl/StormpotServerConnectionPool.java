package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.HttpFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stormpot.Allocator;
import stormpot.BlazePool;
import stormpot.Completion;
import stormpot.Config;
import stormpot.Expiration;
import stormpot.LifecycledPool;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.Timeout;

/** Stormpot-backed implementation of {@link ServerConnectionPool}. */
public class StormpotServerConnectionPool implements ServerConnectionPool {
  private static final Logger LOG = LoggerFactory.getLogger(StormpotServerConnectionPool.class);

  private final DefaultHttpProxyServer proxyServer;
  private final io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler;
  private final int maxConnectionsPerHost;
  private final int maxConnections;
  private final ConcurrentMap<String, LifecycledPool<StormpotPooledConnection>> poolsByHost =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<ProxyToServerConnection, StormpotPooledConnection>
      poolablesByConnection = new ConcurrentHashMap<>();
  private final ConcurrentMap<Channel, Queue<PendingRequest>> pendingRequestsByChannel =
      new ConcurrentHashMap<>();
  private final ThreadLocal<ConnectionContext> creationContext = new ThreadLocal<>();
  private final Semaphore totalConnectionPermits;
  @Nullable private volatile Duration idleTimeout;
  private volatile boolean connectionValidationEnabled = false;

  private final AtomicLong borrowCount = new AtomicLong(0);
  private final AtomicLong returnCount = new AtomicLong(0);
  private final AtomicLong evictionCount = new AtomicLong(0);
  private final AtomicLong validationFailureCount = new AtomicLong(0);

  public StormpotServerConnectionPool(
      DefaultHttpProxyServer proxyServer,
      io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler,
      int maxConnectionsPerHost,
      int maxConnections) {
    this.proxyServer = proxyServer;
    this.globalTrafficShapingHandler = globalTrafficShapingHandler;
    this.maxConnectionsPerHost =
        maxConnectionsPerHost > 0
            ? maxConnectionsPerHost
            : ConcurrentMapServerConnectionPool.DEFAULT_MAX_CONNECTIONS_PER_HOST;
    this.maxConnections =
        maxConnections > 0
            ? maxConnections
            : ConcurrentMapServerConnectionPool.DEFAULT_MAX_TOTAL_CONNECTIONS;
    this.totalConnectionPermits = new Semaphore(this.maxConnections, true);
  }

  @Override
  @Nullable
  public ProxyToServerConnection getOrCreateConnection(
      String serverHostAndPort,
      ClientToProxyConnection clientConnection,
      HttpFilters initialFilters,
      HttpRequest initialHttpRequest) {
    ConnectionContext context =
        new ConnectionContext(clientConnection, initialFilters, initialHttpRequest);
    creationContext.set(context);
    try {
      LifecycledPool<StormpotPooledConnection> pool =
          poolsByHost.computeIfAbsent(serverHostAndPort, this::createPool);
      Timeout timeout = new Timeout(1, TimeUnit.MILLISECONDS);
      StormpotPooledConnection pooled = pool.claim(timeout);
      if (pooled == null) {
        return null;
      }
      poolablesByConnection.put(pooled.connection, pooled);
      borrowCount.incrementAndGet();
      return pooled.connection;
    } catch (PoolException e) {
      LOG.warn("Failed to claim Stormpot connection for {}", serverHostAndPort, e);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while claiming Stormpot connection for {}", serverHostAndPort, e);
      return null;
    } finally {
      creationContext.remove();
    }
  }

  @Override
  public void releaseConnection(ProxyToServerConnection connection) {
    if (connection == null) {
      return;
    }
    StormpotPooledConnection pooled = poolablesByConnection.get(connection);
    if (pooled != null) {
      pooled.release();
      returnCount.incrementAndGet();
    }
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
    StormpotPooledConnection pooled = poolablesByConnection.remove(connection);
    if (pooled != null) {
      try {
        pooled.connection.close();
      } catch (Exception e) {
        LOG.debug("Failed to close Stormpot connection for {}", serverHostAndPort, e);
      }
      try {
        pooled.release();
      } catch (Exception e) {
        LOG.debug("Failed to release Stormpot connection for {}", serverHostAndPort, e);
      }
    }
  }

  @Override
  public void closeAll() {
    for (LifecycledPool<StormpotPooledConnection> pool : poolsByHost.values()) {
      Completion completion = pool.shutdown();
      try {
        completion.await(new Timeout(5, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.warn("Interrupted while shutting down Stormpot pool", e);
      }
    }
    poolsByHost.clear();
    poolablesByConnection.clear();
    pendingRequestsByChannel.clear();
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
    LOG.info("Stormpot idle timeout set to {}", idleTimeout);
  }

  @Override
  @Nullable
  public Duration getIdleTimeout() {
    return idleTimeout;
  }

  @Override
  public void setConnectionValidationEnabled(boolean validationEnabled) {
    this.connectionValidationEnabled = validationEnabled;
    LOG.info("Stormpot connection validation enabled: {}", validationEnabled);
  }

  @Override
  public boolean isConnectionValidationEnabled() {
    return connectionValidationEnabled;
  }

  @Override
  public PoolMetrics getMetrics() {
    int total = poolablesByConnection.size();
    int idle = 0;
    int active = total;
    return new PoolMetrics(
        total,
        active,
        idle,
        borrowCount.get(),
        returnCount.get(),
        evictionCount.get(),
        validationFailureCount.get());
  }

  private LifecycledPool<StormpotPooledConnection> createPool(String serverHostAndPort) {
    StormpotAllocator allocator = new StormpotAllocator(serverHostAndPort);
    Config<StormpotPooledConnection> config = new Config<>();
    config.setAllocator(allocator);
    config.setSize(maxConnectionsPerHost);
    config.setExpiration(new ConnectionExpiration());
    return new BlazePool<>(config);
  }

  private class StormpotAllocator implements Allocator<StormpotPooledConnection> {
    private final String serverHostAndPort;

    private StormpotAllocator(String serverHostAndPort) {
      this.serverHostAndPort = serverHostAndPort;
    }

    @Override
    public StormpotPooledConnection allocate(Slot slot) throws Exception {
      ConnectionContext context = creationContext.get();
      if (context == null) {
        throw new IllegalStateException("Missing connection creation context");
      }
      if (!totalConnectionPermits.tryAcquire()) {
        throw new IllegalStateException("Pool exhausted: max connections reached");
      }
      try {
        ProxyToServerConnection connection =
            ProxyToServerConnection.createForPool(
                proxyServer,
                StormpotServerConnectionPool.this,
                context.clientConnection,
                serverHostAndPort,
                context.initialFilters,
                context.initialHttpRequest,
                globalTrafficShapingHandler);
        if (connection == null) {
          throw new IllegalStateException(
              "Unable to create pooled connection for " + serverHostAndPort);
        }
        StormpotPooledConnection pooled = new StormpotPooledConnection(slot, connection);
        poolablesByConnection.put(connection, pooled);
        return pooled;
      } catch (Exception e) {
        totalConnectionPermits.release();
        throw e;
      }
    }

    @Override
    public void deallocate(StormpotPooledConnection poolable) {
      try {
        poolable.connection.close();
      } finally {
        poolablesByConnection.remove(poolable.connection);
        totalConnectionPermits.release();
      }
    }
  }

  private static class StormpotPooledConnection implements Poolable {
    private final Slot slot;
    private final ProxyToServerConnection connection;

    private StormpotPooledConnection(Slot slot, ProxyToServerConnection connection) {
      this.slot = slot;
      this.connection = connection;
    }

    @Override
    public void release() {
      slot.release(this);
    }
  }

  private static class ConnectionExpiration implements Expiration<StormpotPooledConnection> {
    @Override
    public boolean hasExpired(stormpot.SlotInfo<? extends StormpotPooledConnection> slotInfo)
        throws Exception {
      StormpotPooledConnection pooled = slotInfo.getPoolable();
      return pooled == null || !pooled.connection.isConnected();
    }
  }

  private static class ConnectionContext {
    private final ClientToProxyConnection clientConnection;
    private final HttpFilters initialFilters;
    private final HttpRequest initialHttpRequest;

    private ConnectionContext(
        ClientToProxyConnection clientConnection,
        HttpFilters initialFilters,
        HttpRequest initialHttpRequest) {
      this.clientConnection = clientConnection;
      this.initialFilters = initialFilters;
      this.initialHttpRequest = initialHttpRequest;
    }
  }
}
