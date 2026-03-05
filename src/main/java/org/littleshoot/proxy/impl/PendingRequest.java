package org.littleshoot.proxy.impl;

import io.netty.handler.codec.http.HttpRequest;

/** Tracks a pending request and its associated client connection for HTTP pipelining. */
public class PendingRequest {
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
