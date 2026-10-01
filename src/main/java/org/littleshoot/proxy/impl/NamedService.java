package org.littleshoot.proxy.impl;

/**
 * A service that is discovered through the Java {@link java.util.ServiceLoader} and selected by
 * name through a {@link NamedServiceLoader}.
 *
 * <p>Implementations must expose a public no-argument constructor and return a stable, non-blank
 * name that is unique (case-insensitively) among the implementations registered for the same
 * service type on the classpath.
 */
public interface NamedService {

  /**
   * Returns the name used to select this implementation. Matched case-insensitively by {@link
   * NamedServiceLoader}.
   *
   * @return the implementation name
   */
  String getName();
}
