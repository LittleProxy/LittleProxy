package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import org.jspecify.annotations.Nullable;

/**
 * The default pool, except that it pauses whenever it finds no pending request for a channel.
 * Registered through {@code META-INF/services/org.littleshoot.proxy.impl.ServerConnectionPool} in
 * the test resources.
 *
 * <p>When a response completes and no request is pending, the server connection clears its current
 * request. The pause widens the window between that lookup and the clear, so that a keep-alive
 * client's next request reliably arrives inside it (#859).
 */
public class SlowEmptyQueueServerConnectionPool extends ConcurrentMapServerConnectionPool {

  public static final String NAME = "slow-empty-queue";

  /** Long enough for the client's next request to reach the server connection. */
  static final int EMPTY_QUEUE_PAUSE_MILLIS = 500;

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public @Nullable PendingRequest peekPendingRequest(Channel channel) {
    return pauseIfNull(super.peekPendingRequest(channel));
  }

  @Override
  public @Nullable PendingRequest removePendingRequest(Channel channel) {
    return pauseIfNull(super.removePendingRequest(channel));
  }

  private static @Nullable PendingRequest pauseIfNull(@Nullable PendingRequest pending) {
    if (pending == null) {
      try {
        Thread.sleep(EMPTY_QUEUE_PAUSE_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return pending;
  }
}
