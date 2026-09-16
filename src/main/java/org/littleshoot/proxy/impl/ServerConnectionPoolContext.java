package org.littleshoot.proxy.impl;

import static java.util.Objects.requireNonNull;

import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.HttpProxyServer;

/**
 * Carries the dependencies and options handed to a {@link ServerConnectionPool} implementation
 * during {@link ServerConnectionPool#initialize(ServerConnectionPoolContext)}.
 *
 * <p>The {@link #getOptions()} map is populated with the standard keys documented on this class for
 * the settings every pool implementation should understand, plus any implementation-specific keys
 * supplied through the bootstrap. Values may be typed objects or raw {@link String}s from a
 * properties file; implementations should normalize them with {@link PoolConfigUtils}.
 */
public final class ServerConnectionPoolContext {

  /** Standard option key: maximum number of connections per host:port ({@link Integer}). */
  public static final String OPTION_MAX_CONNECTIONS_PER_HOST = "maxConnectionsPerHost";

  /** Standard option key: maximum total number of connections ({@link Integer}). */
  public static final String OPTION_MAX_CONNECTIONS = "maxConnections";

  /**
   * Standard option key: idle timeout before a pooled connection is evicted, as a {@link
   * java.time.Duration} or absent when eviction is disabled.
   */
  public static final String OPTION_IDLE_TIMEOUT = "idleTimeout";

  private final HttpProxyServer server;
  @Nullable private final GlobalTrafficShapingHandler globalTrafficShapingHandler;
  private final Map<String, Object> options;

  private ServerConnectionPoolContext(Builder builder) {
    this.server = builder.server;
    this.globalTrafficShapingHandler = builder.globalTrafficShapingHandler;
    this.options = Collections.unmodifiableMap(new LinkedHashMap<>(builder.options));
  }

  /** Returns the proxy server this pool serves. */
  public HttpProxyServer getServer() {
    return server;
  }

  /**
   * Returns the traffic shaping handler of the server, or null when throttling is disabled.
   *
   * @return the traffic shaping handler, or null when throttling is disabled
   */
  @Nullable
  public GlobalTrafficShapingHandler getGlobalTrafficShapingHandler() {
    return globalTrafficShapingHandler;
  }

  /** Returns the read-only map of options for the pool implementation. */
  public Map<String, Object> getOptions() {
    return options;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ServerConnectionPoolContext}. */
  public static final class Builder {
    private HttpProxyServer server;
    @Nullable private GlobalTrafficShapingHandler globalTrafficShapingHandler;
    private final Map<String, Object> options = new LinkedHashMap<>();

    public Builder server(HttpProxyServer server) {
      this.server = server;
      return this;
    }

    public Builder globalTrafficShapingHandler(
        @Nullable GlobalTrafficShapingHandler globalTrafficShapingHandler) {
      this.globalTrafficShapingHandler = globalTrafficShapingHandler;
      return this;
    }

    public Builder option(String key, Object value) {
      options.put(requireNonNull(key, "key must not be null"), value);
      return this;
    }

    public Builder options(Map<String, Object> options) {
      this.options.putAll(options);
      return this;
    }

    public ServerConnectionPoolContext build() {
      return new ServerConnectionPoolContext(this);
    }
  }
}
