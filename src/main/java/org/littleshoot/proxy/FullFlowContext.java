package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.littleshoot.proxy.impl.ClientToProxyConnection;
import org.littleshoot.proxy.impl.ProxyToServerConnection;

/**
 * Extension of {@link FlowContext} that provides additional information (which we know after
 * actually processing the request from the client).
 */
public class FullFlowContext extends FlowContext {
  private final String serverHostAndPort;
  private final ChainedProxy chainedProxy;
  private final ChannelHandlerContext ctx;
  private final String serverConnectionId;
  private final Map<String, Long> timingData = new ConcurrentHashMap<>();

  public FullFlowContext(
      ClientToProxyConnection clientConnection, ProxyToServerConnection serverConnection) {
    super(clientConnection);
    serverHostAndPort = serverConnection.getServerHostAndPort();
    chainedProxy = serverConnection.getChainedProxy();
    this.ctx = serverConnection.getContext();
    this.serverConnectionId = serverConnection.getId();
  }

  @Override
  public String getFlowId() {
    return super.getFlowId() + '.' + serverConnectionId;
  }

  /** The host and port for the server (i.e. the ultimate endpoint). */
  public String getServerHostAndPort() {
    return serverHostAndPort;
  }

  /** The chained proxy (if proxy chaining). */
  public ChainedProxy getChainedProxy() {
    return chainedProxy;
  }

  /** The proxy to server channel context. */
  public ChannelHandlerContext getProxyToServerContext() {
    return ctx;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    if (!super.equals(o)) return false;
    FullFlowContext that = (FullFlowContext) o;
    return Objects.equals(getFlowId(), that.getFlowId());
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), Objects.hash(getFlowId()));
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
    return Optional.ofNullable(super.getTimingData(key)).orElseGet(() -> timingData.get(key));
  }

  /**
   * Gets all timing data for this flow.
   *
   * @return map of all timing data
   */
  public Map<String, Long> getTimings() {
    Map<String, Long> timings = super.getTimings();
    timings.putAll(timingData);
    return timings;
  }
}
