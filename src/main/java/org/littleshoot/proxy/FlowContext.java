package org.littleshoot.proxy;

import com.github.f4b6a3.ulid.UlidCreator;
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
  private final InetSocketAddress clientAddress;
  private final SSLSession clientSslSession;
  private final String connectionId;
  private final Map<String, Long> timingData = new ConcurrentHashMap<>();

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

  /**
   * Gets the flow ID for this context.
   *
   * @return the flow ID, or null if not set
   */
  public String getFlowId() {
    return connectionId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FlowContext)) return false;
    FlowContext that = (FlowContext) o;
    return connectionId.equals(that.connectionId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(connectionId);
  }

  /**
   * Generates a unique flow ID for tracing requests across the proxy.
   *
   * @return unique flow identifier
   */
  private String generateFlowId() {
    return UlidCreator.getUlid().toString();
  }
}
