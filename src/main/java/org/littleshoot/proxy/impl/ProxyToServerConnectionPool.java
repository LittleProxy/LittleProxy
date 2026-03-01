package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 */
public class ProxyToServerConnectionPool {
  /** The map of server host:port to ProxyToServerConnection. */
  private final ConcurrentMap<String, ProxyToServerConnection> connectionsByHostAndPort =
      new ConcurrentHashMap<>();

  /** Track pending requests to route responses to the correct client connection. */
  private final ConcurrentMap<Channel, PendingRequest> pendingRequestsByChannel =
      new ConcurrentHashMap<>();

  /** The proxy server that owns this pool. */
  private final DefaultHttpProxyServer proxyServer;

  /** The global traffic shaping handler. */
  private final io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler;

  public ProxyToServerConnectionPool(
      DefaultHttpProxyServer proxyServer,
      io.netty.handler.traffic.GlobalTrafficShapingHandler globalTrafficShapingHandler) {
    this.proxyServer = proxyServer;
    this.globalTrafficShapingHandler = globalTrafficShapingHandler;
  }

  /**
   * Gets a connection for the given host and port, or creates one if it doesn't exist.
   *
   * @param serverHostAndPort the server host and port key
   * @param clientConnection the client connection that needs the server connection
   * @param initialFilters the initial HTTP filters
   * @param initialHttpRequest the initial HTTP request
   * @return the ProxyToServerConnection, or null if creation failed
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

    // Need to create a new connection
    synchronized (this) {
      // Double-check after acquiring lock
      existingConnection = connectionsByHostAndPort.get(serverHostAndPort);
      if (existingConnection != null && existingConnection.isConnected()) {
        if (existingConnection.isAvailableForNewRequest()) {
          return existingConnection;
        }
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
   * Registers a pending request so that when a response comes back, we know which client connection
   * to send it to.
   *
   * @param channel the server channel
   * @param clientConnection the client connection that made the request
   * @param request the HTTP request
   */
  public void registerPendingRequest(
      Channel channel, ClientToProxyConnection clientConnection, HttpRequest request) {
    pendingRequestsByChannel.put(channel, new PendingRequest(clientConnection, request));
  }

  /**
   * Gets and removes the pending request for the given channel.
   *
   * @param channel the server channel
   * @return the pending request, or null if none found
   */
  @Nullable
  public PendingRequest removePendingRequest(Channel channel) {
    return pendingRequestsByChannel.remove(channel);
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
  }

  /** Tracks a pending request and its associated client connection. */
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
