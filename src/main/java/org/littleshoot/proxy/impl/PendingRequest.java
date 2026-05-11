package org.littleshoot.proxy.impl;

import io.netty.handler.codec.http.HttpRequest;
import org.littleshoot.proxy.HttpFilters;

/**
 * Tracks a pending request and its associated client connection and filters for HTTP pipelining.
 */
public class PendingRequest {
  private final ClientToProxyConnection clientConnection;
  private final HttpRequest request;
  private final HttpFilters filters;
  private final long timestamp;

  public PendingRequest(
      ClientToProxyConnection clientConnection, HttpRequest request, HttpFilters filters) {
    this.clientConnection = clientConnection;
    this.request = request;
    this.filters = filters;
    this.timestamp = System.currentTimeMillis();
  }

  public ClientToProxyConnection getClientConnection() {
    return clientConnection;
  }

  public HttpRequest getRequest() {
    return request;
  }

  public HttpFilters getFilters() {
    return filters;
  }

  public long getTimestamp() {
    return timestamp;
  }
}
