package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.jspecify.annotations.Nullable;
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
      ClientToProxyConnection clientConnection,
      HttpFilters initialFilters,
      HttpRequest initialHttpRequest) {
    ConnectionContext context =
        new ConnectionContext(clientConnection, initialFilters, initialHttpRequest);
    creationContext.set(context);
    try {
      return pool.borrowObject(serverHostAndPort);
    } catch (Exception e) {
      LOG.warn("Failed to borrow connection for {}", serverHostAndPort, e);
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
  public void removeConnection(String serverHostAndPort, ProxyToServerConnection connection) {
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
