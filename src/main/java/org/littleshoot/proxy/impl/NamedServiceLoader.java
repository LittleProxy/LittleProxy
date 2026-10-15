package org.littleshoot.proxy.impl;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Loads a {@link NamedService} implementation of type {@code T} through the Java {@link
 * ServiceLoader}, selected by the name returned by {@link NamedService#getName()}.
 *
 * <p>Generic over the service type and reusable for any pluggable service selected by name (for
 * example the server connection pool). Names are matched case-insensitively, so a lowercase
 * configuration spelling (e.g. {@code concurrent_map}) resolves to an implementation however it
 * capitalizes its {@code getName()}.
 *
 * <p>Selection fails fast: the requested name must match exactly one registered implementation. An
 * unknown name or a name shared by several implementations raises rather than silently selecting a
 * default. Only implementations matching the requested name are checked for ambiguity, so unrelated
 * third-party implementations that happen to collide are harmless.
 *
 * @param <T> the service type
 */
public final class NamedServiceLoader<T extends NamedService> {

  private final Class<T> serviceType;
  private final Iterable<T> candidates;

  /**
   * Creates a loader that discovers implementations of {@code serviceType} through {@link
   * ServiceLoader}.
   *
   * @param serviceType the service interface to load
   */
  public NamedServiceLoader(Class<T> serviceType) {
    this(serviceType, ServiceLoader.load(serviceType));
  }

  NamedServiceLoader(Class<T> serviceType, Iterable<T> candidates) {
    this.serviceType = requireNonNull(serviceType, "serviceType must not be null");
    this.candidates = requireNonNull(candidates, "candidates must not be null");
  }

  /**
   * Loads the implementation named {@code requestedName}.
   *
   * @param requestedName the requested implementation name, matched case-insensitively
   * @return the selected, not-yet-initialized implementation
   * @throws IllegalArgumentException if several implementations share the requested name
   * @throws IllegalStateException if no implementation matches the requested name, an
   *     implementation publishes a blank name, or the implementations could not be instantiated
   */
  public T load(String requestedName) {
    requireNonNull(requestedName, "requestedName must not be null");
    String requested = requestedName.trim().toLowerCase(Locale.ROOT);

    T match = null;
    List<String> availableNames = new ArrayList<>();
    try {
      for (T candidate : candidates) {
        String name = candidate.getName();
        if (name == null || name.trim().isEmpty()) {
          throw new IllegalStateException(
              "Service implementation "
                  + candidate.getClass().getName()
                  + " must expose a non-blank name via getName()");
        }
        availableNames.add(name);
        if (!name.toLowerCase(Locale.ROOT).equals(requested)) {
          continue;
        }
        if (match != null) {
          throw new IllegalArgumentException(
              "Ambiguous service implementations named '" + requestedName + "'");
        }
        match = candidate;
      }
    } catch (ServiceConfigurationError e) {
      throw new IllegalStateException(
          "Failed to load " + serviceType.getName() + " implementations via ServiceLoader", e);
    }

    if (match == null) {
      throw new IllegalStateException(
          "No service implementation named '"
              + requestedName
              + "' found (available: "
              + availableNames
              + ")");
    }
    return match;
  }
}
