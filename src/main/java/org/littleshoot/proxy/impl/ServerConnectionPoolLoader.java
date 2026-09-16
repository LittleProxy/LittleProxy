package org.littleshoot.proxy.impl;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@link ServerConnectionPool} implementations through the Java {@link ServiceLoader}.
 *
 * <p>Implementations are registered in {@code META-INF/services/...ServerConnectionPool} and must
 * expose a public no-argument constructor. One is selected by the name returned by {@link
 * ServerConnectionPool#getName()}. When the requested name is not found, or no implementation is
 * registered at all, the built-in {@link ConcurrentMapServerConnectionPool} is used as a fallback.
 */
public final class ServerConnectionPoolLoader {

  /** Name of the built-in {@link ConcurrentMapServerConnectionPool} implementation. */
  public static final String DEFAULT_POOL_NAME = "CONCURRENT_MAP";

  private static final Logger LOG = LoggerFactory.getLogger(ServerConnectionPoolLoader.class);

  private final Iterable<ServerConnectionPool> candidates;

  public ServerConnectionPoolLoader() {
    this(ServiceLoader.load(ServerConnectionPool.class));
  }

  ServerConnectionPoolLoader(Iterable<ServerConnectionPool> candidates) {
    this.candidates = requireNonNull(candidates, "candidates must not be null");
  }

  /**
   * Loads the pool implementation named {@code poolName}, initializes it with the given context,
   * and returns it.
   *
   * @param poolName the requested pool implementation name
   * @param context the context to initialize the pool with
   * @return the initialized pool implementation
   * @throws IllegalArgumentException if several implementations share the requested name, or if the
   *     implementation factory publishes a blank name
   * @throws IllegalStateException if a registered implementation could not be instantiated
   */
  public ServerConnectionPool load(String poolName, ServerConnectionPoolContext context) {
    requireNonNull(poolName, "poolName must not be null");
    requireNonNull(context, "context must not be null");

    Map<String, ServerConnectionPool> poolsByName = new HashMap<>();
    List<String> availableNames = new ArrayList<>();
    try {
      for (ServerConnectionPool pool : candidates) {
        String name = pool.getName();
        if (name == null || name.trim().isEmpty()) {
          throw new IllegalStateException(
              "Server connection pool implementation "
                  + pool.getClass().getName()
                  + " must expose a non-blank name via getName()");
        }
        availableNames.add(name);
        if (poolsByName.putIfAbsent(name, pool) != null) {
          throw new IllegalArgumentException(
              "Ambiguous server connection pool implementations named '" + name + "'");
        }
      }
    } catch (ServiceConfigurationError e) {
      throw new IllegalStateException(
          "Failed to load server connection pool implementations via ServiceLoader", e);
    }

    ServerConnectionPool selected = poolsByName.get(poolName);
    if (selected == null) {
      LOG.warn(
          "No server connection pool implementation named '{}' found (available: {})."
              + " Falling back to '{}'.",
          poolName,
          availableNames,
          DEFAULT_POOL_NAME);
      selected = poolsByName.get(DEFAULT_POOL_NAME);
    }
    if (selected == null) {
      LOG.warn(
          "No server connection pool implementation named '{}' is registered via ServiceLoader;"
              + " using {} directly as a last resort.",
          DEFAULT_POOL_NAME,
          ConcurrentMapServerConnectionPool.class.getName());
      selected = new ConcurrentMapServerConnectionPool();
    }

    selected.initialize(context);
    return selected;
  }
}
