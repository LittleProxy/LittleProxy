package org.littleshoot.proxy;

import java.net.InetSocketAddress;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import org.littleshoot.proxy.impl.ClientToProxyConnection;

/**
 * Encapsulates contextual information for flow information that's being reported to a {@link
 * ActivityTracker}.
 */
public class FlowContext {
  private final InetSocketAddress clientAddress;
  private final SSLSession clientSslSession;
  private final long connectionId;

  public FlowContext(ClientToProxyConnection clientConnection) {
    clientAddress = clientConnection.getClientAddress();
    SSLEngine sslEngine = clientConnection.getSslEngine();
    clientSslSession = sslEngine != null ? sslEngine.getSession() : null;
    this.connectionId = clientConnection.getId();
  }

  /** The address of the client. */
  public InetSocketAddress getClientAddress() {
    return clientAddress;
  }

  /** If using SSL, this returns the {@link SSLSession} on the client connection. */
  public SSLSession getClientSslSession() {
    return clientSslSession;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FlowContext)) return false;
    FlowContext that = (FlowContext) o;
    return connectionId == that.connectionId;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(connectionId);
  }
}
