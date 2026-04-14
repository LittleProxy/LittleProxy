package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.ChainedProxy;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Apache Commons Pool 2 implementation of {@link ServerConnectionPool}. */
public class CommonsPoolServerConnectionPool implements ServerConnectionPool {
  private static final Logger LOG = LoggerFactory.getLogger(CommonsPoolServerConnectionPool.class);

  private final DefaultHttpProxyServer proxyServer;
  private final io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler;
  private final int maxConnectionsPerHost;
  private final int maxConnections;
  private final GenericKeyedObjectPool<String, ProxyToServerConnection> pool;
  private final ConcurrentMap<ProxyToServerConnection, String> connectionKeys =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<Channel, Queue<PendingRequest>> pendingRequestsByChannel =
      new ConcurrentHashMap<>();
  private final ThreadLocal<ConnectionContext> creationContext = new ThreadLocal<>();

  private final AtomicLong borrowCount = new AtomicLong(0);
  private final AtomicLong returnCount = new AtomicLong(0);
  private final AtomicLong evictionCount = new AtomicLong(0);
  private final AtomicLong validationFailureCount = new AtomicLong(0);

  public CommonsPoolServerConnectionPool(
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

    GenericKeyedObjectPoolConfig<ProxyToServerConnection> config =
        new GenericKeyedObjectPoolConfig<>();
    config.setMaxTotal(this.maxConnections);
    config.setMaxTotalPerKey(this.maxConnectionsPerHost);
    config.setBlockWhenExhausted(false);
    config.setMaxWait(Duration.ZERO);

    this.pool = new GenericKeyedObjectPool<>(new ConnectionFactory(), config);
  }

  @Override
  @Nullable
  public ProxyToServerConnection getOrCreateConnection(
      String serverHostAndPort,
      @Nullable InetSocketAddress chainedProxyAddress,
      ClientToProxyConnection clientConnection,
      HttpFilters initialFilters,
      HttpRequest initialHttpRequest) {
    ChainedProxy chainedProxy = resolveChainedProxy(initialHttpRequest, clientConnection);
    String poolKey = computePoolKey(serverHostAndPort, chainedProxyAddress);
    ConnectionContext context =
        new ConnectionContext(chainedProxy, clientConnection, initialFilters, initialHttpRequest);
    creationContext.set(context);
    try {
      ProxyToServerConnection connection = pool.borrowObject(poolKey);
      borrowCount.incrementAndGet();
      return connection;
    } catch (Exception e) {
      LOG.warn("Failed to borrow connection for {}", poolKey, e);
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
    String key = connectionKeys.get(connection);
    if (key == null) {
      return;
    }
    try {
      if (!connection.isConnected()) {
        pool.invalidateObject(key, connection);
        connectionKeys.remove(connection);
      } else {
        pool.returnObject(key, connection);
        returnCount.incrementAndGet();
      }
    } catch (Exception e) {
      LOG.debug("Failed to return connection for {}", key, e);
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
  public void drainPendingRequests(Channel channel) {
    pendingRequestsByChannel.remove(channel);
  }

  @Override
  public void removeConnection(ProxyToServerConnection connection) {
    String key = connectionKeys.remove(connection);
    if (key == null) {
      return;
    }
    try {
      pool.invalidateObject(key, connection);
    } catch (Exception e) {
      LOG.debug("Failed to invalidate connection for {}", key, e);
    }
  }

  @Override
  public void closeAll() {
    pool.close();
    pendingRequestsByChannel.clear();
    connectionKeys.clear();
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
    if (idleTimeout != null && idleTimeout.toMillis() > 0) {
      pool.setTimeBetweenEvictionRuns(Duration.ofMillis(idleTimeout.toMillis() / 2));
      pool.setSoftMinEvictableIdleTimeMillis(idleTimeout.toMillis());
      pool.setTestWhileIdle(true);
      LOG.info(
          "Enabled CommonsPool2 idle eviction: timeout={}ms, evict={}ms",
          idleTimeout.toMillis(),
          idleTimeout.toMillis() / 2);
    } else {
      pool.setTimeBetweenEvictionRuns(Duration.ZERO);
      pool.setTestWhileIdle(false);
      LOG.info("Disabled CommonsPool2 idle eviction");
    }
  }

  @Override
  @Nullable
  public Duration getIdleTimeout() {
    long timeBetweenRuns = pool.getTimeBetweenEvictionRuns().toMillis();
    return timeBetweenRuns > 0 ? Duration.ofMillis(timeBetweenRuns * 2) : null;
  }

  @Override
  public void setConnectionValidationEnabled(boolean validationEnabled) {
    pool.setTestOnBorrow(validationEnabled);
    pool.setTestOnReturn(validationEnabled);
    LOG.info("CommonsPool2 connection validation enabled: {}", validationEnabled);
  }

  @Override
  public boolean isConnectionValidationEnabled() {
    return pool.getTestOnBorrow();
  }

  @Override
  public PoolMetrics getMetrics() {
    int total = pool.getNumActive() + pool.getNumIdle();
    int active = pool.getNumActive();
    int idle = pool.getNumIdle();
    return new PoolMetrics(
        total,
        active,
        idle,
        borrowCount.get(),
        returnCount.get(),
        evictionCount.get(),
        validationFailureCount.get());
  }

  private class ConnectionFactory
      extends BaseKeyedPooledObjectFactory<String, ProxyToServerConnection> {
    @Override
    public ProxyToServerConnection create(String key) throws Exception {
      ConnectionContext context = creationContext.get();
      if (context == null) {
        throw new IllegalStateException("Missing connection creation context");
      }
      ProxyToServerConnection connection =
          ProxyToServerConnection.createForPool(
              proxyServer,
              CommonsPoolServerConnectionPool.this,
              context.clientConnection,
              key,
              context.chainedProxy,
              context.initialFilters,
              context.initialHttpRequest,
              globalTrafficShapingHandler);
      if (connection == null) {
        throw new IllegalStateException("Unable to create pooled connection for " + key);
      }
      connectionKeys.put(connection, key);
      return connection;
    }

    @Override
    public PooledObject<ProxyToServerConnection> wrap(ProxyToServerConnection value) {
      return new DefaultPooledObject<>(value);
    }

    @Override
    public boolean validateObject(String key, PooledObject<ProxyToServerConnection> pooledObject) {
      ProxyToServerConnection connection = pooledObject.getObject();
      if (connection == null || !connection.isConnected()) {
        validationFailureCount.incrementAndGet();
        return false;
      }
      return true;
    }
  }

  @Nullable
  private ChainedProxy resolveChainedProxy(
      HttpRequest httpRequest, ClientToProxyConnection clientConnection) {
    ChainedProxyManager chainedProxyManager = proxyServer.getChainProxyManager();
    if (chainedProxyManager == null) {
      return null;
    }
    Queue<ChainedProxy> chainedProxies = new ConcurrentLinkedQueue<>();
    chainedProxyManager.lookupChainedProxies(
        httpRequest, chainedProxies, clientConnection.getClientDetails());
    if (chainedProxies.isEmpty()) {
      return null;
    }
    return chainedProxies.poll();
  }

  private static class ConnectionContext {
    private final ChainedProxy chainedProxy;
    private final ClientToProxyConnection clientConnection;
    private final HttpFilters initialFilters;
    private final HttpRequest initialHttpRequest;

    private ConnectionContext(
        ChainedProxy chainedProxy,
        ClientToProxyConnection clientConnection,
        HttpFilters initialFilters,
        HttpRequest initialHttpRequest) {
      this.chainedProxy = chainedProxy;
      this.clientConnection = clientConnection;
      this.initialFilters = initialFilters;
      this.initialHttpRequest = initialHttpRequest;
    }
  }
}
