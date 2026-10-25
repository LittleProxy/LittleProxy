package org.littleshoot.proxy.impl;

import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.HttpProxyServer;

/**
 * Test-only {@link ServerConnectionPool} whose {@link #initialize} can be scripted to fail through
 * the {@value #MODE_OPTION} option, used to verify how {@link DefaultHttpProxyServer} reacts to a
 * pool that cannot be initialized. The behaviour is kept in per-instance state (no statics), so the
 * tests stay safe under the suite's parallel execution:
 *
 * <ul>
 *   <li>{@value #EXCEPTION} (default): the first {@code initialize} on an instance throws {@link
 *       IllegalArgumentException}, later ones succeed.
 *   <li>{@value #ERROR}: the first {@code initialize} throws {@link NoClassDefFoundError}, later
 *       ones succeed.
 * </ul>
 *
 * <p>Because an instance only fails on its first {@code initialize}, a caller can tell whether the
 * server retried the same instance (a later call succeeds) or resolved a fresh one (it fails
 * again). Extends {@link OptionsCapturingServerConnectionPool} to inherit its no-op pooling
 * methods.
 */
public class FailingServerConnectionPool extends OptionsCapturingServerConnectionPool {

  static final String NAME = "FAILING_POOL";
  static final String MODE_OPTION = "mode";
  static final String EXCEPTION = "exception";
  static final String ERROR = "error";

  private int attempts;

  public FailingServerConnectionPool() {}

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void initialize(
      HttpProxyServer server,
      @Nullable GlobalTrafficShapingHandler globalTrafficShapingHandler,
      Map<String, Object> options) {
    attempts++;
    if (attempts == 1) {
      if (ERROR.equals(options.get(MODE_OPTION))) {
        throw new NoClassDefFoundError("boom");
      }
      throw new IllegalArgumentException("bad option");
    }
  }
}
