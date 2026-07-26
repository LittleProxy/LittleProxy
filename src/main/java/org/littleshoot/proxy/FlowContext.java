package org.littleshoot.proxy;

import io.netty.handler.codec.haproxy.HAProxyMessage;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import org.littleshoot.proxy.impl.ClientToProxyConnection;

/**
 * Encapsulates contextual information for flow information that's being reported to a {@link
 * ActivityTracker}.
 */
public class FlowContext {
  private final ClientToProxyConnection clientConnection;
  private final long connectionId;
  private final Map<String, Long> timingData = new ConcurrentHashMap<>();

  public FlowContext(ClientToProxyConnection clientConnection) {
    this.clientConnection = clientConnection;
    this.connectionId = clientConnection.getId();
  }

  /**
   * The client's address: the PROXY header's source address when PROXY protocol is in use,
   * otherwise the TCP peer. Resolved lazily so a header received after construction is reflected.
   */
  public InetSocketAddress getClientAddress() {
    HAProxyMessage haProxyMessage = clientConnection.getHaProxyMessage();
    if (haProxyMessage != null
        && haProxyMessage.sourceAddress() != null
        && !haProxyMessage.sourceAddress().isBlank()) {
      return new InetSocketAddress(haProxyMessage.sourceAddress(), haProxyMessage.sourcePort());
    }
    return clientConnection.getClientAddress();
  }

  /** If using SSL, this returns the {@link SSLSession} on the client connection. */
  public SSLSession getClientSslSession() {
    SSLEngine sslEngine = clientConnection.getSslEngine();
    return sslEngine != null ? sslEngine.getSession() : null;
  }

  /**
   * Stores timing data for this flow.
   *
   * @param key the timing metric key
   * @param value the timing value in milliseconds
   */
  public void setTimingData(String key, Long value) {
    Objects.requireNonNull(key, "timing key must not be null");
    Objects.requireNonNull(value, "timing value must not be null");
    timingData.put(key, value);
  }

  /**
   * Retrieves timing data for this flow.
   *
   * @param key the timing metric key
   * @return the timing value in milliseconds, or null if not available
   */
  public Long getTimingData(String key) {
    return timingData.get(key);
  }

  /**
   * Gets all timing data for this flow.
   *
   * @return map of all timing data
   */
  public Map<String, Long> getTimings() {
    return Map.copyOf(timingData);
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
