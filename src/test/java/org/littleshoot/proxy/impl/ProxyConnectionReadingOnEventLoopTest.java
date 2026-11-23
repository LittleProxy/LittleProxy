package org.littleshoot.proxy.impl;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.impl.ProxyConnection.TRAFFIC_SHAPING_READ_SUSPENDED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpFiltersSource;

/**
 * {@link ProxyConnection#stopReading()} and {@link ProxyConnection#resumeReading()} are called from
 * other threads too (e.g. the client connection's event loop pausing/resuming a server connection),
 * but must not interleave with the traffic shaping handler running on the channel's own event loop.
 */
class ProxyConnectionReadingOnEventLoopTest {
  private final DefaultEventLoop eventLoop = new DefaultEventLoop();
  private final LocalChannel channel = new LocalChannel();
  private ClientToProxyConnection connection;

  @BeforeEach
  void setUp() throws Exception {
    eventLoop.register(channel).sync();
    connection = eventLoop.submit(this::createConnection).get(5, SECONDS);
  }

  @AfterEach
  void tearDown() throws Exception {
    channel.close().sync();
    eventLoop.shutdownGracefully(0, 0, SECONDS).sync();
  }

  @Test
  void resumeReadingFromAnotherThreadCannotSlipIntoTrafficShapingSuspension() throws Exception {
    runOnEventLoop(
        () -> {
          // the traffic shaping handler suspends reads in two steps...
          channel.config().setAutoRead(false);
          // ...and another thread resumes reading in between
          runOnAnotherThread(connection::resumeReading);
          channel.attr(TRAFFIC_SHAPING_READ_SUSPENDED).set(true);
        });
    runOnEventLoop(() -> {}); // let queued tasks run

    assertThat(channel.config().isAutoRead()).isFalse();
  }

  @Test
  void latestCallWinsWhenQueuedCallRunsAfterCallOnEventLoop() throws Exception {
    runOnEventLoop(
        () -> {
          runOnAnotherThread(connection::resumeReading); // queued to run after this task
          connection.stopReading(); // runs immediately
        });
    runOnEventLoop(() -> {}); // let queued tasks run

    assertThat(channel.config().isAutoRead()).isFalse();
  }

  @Test
  void stopAndResumeReadingFromAnotherThread() throws Exception {
    connection.stopReading();
    runOnEventLoop(() -> {});
    assertThat(channel.config().isAutoRead()).isFalse();

    connection.resumeReading();
    runOnEventLoop(() -> {});
    assertThat(channel.config().isAutoRead()).isTrue();
  }

  private void runOnEventLoop(Runnable task) throws Exception {
    eventLoop.submit(task).get(5, SECONDS);
  }

  private static void runOnAnotherThread(Runnable task) {
    Thread thread = new Thread(task);
    thread.start();
    try {
      thread.join(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private ClientToProxyConnection createConnection() {
    DefaultHttpProxyServer proxyServer = mock();
    when(proxyServer.getChainProxyManager()).thenReturn(mock(ChainedProxyManager.class));
    when(proxyServer.getFiltersSource()).thenReturn(mock(HttpFiltersSource.class));
    when(proxyServer.getMaxInitialLineLength()).thenReturn(8192);
    when(proxyServer.getMaxHeaderSize()).thenReturn(16384);
    when(proxyServer.getMaxChunkSize()).thenReturn(16384);
    when(proxyServer.getProxyAlias()).thenReturn("test");
    when(proxyServer.getActivityTrackers()).thenReturn(Collections.emptyList());
    return new ClientToProxyConnection(proxyServer, null, false, channel.pipeline(), mock());
  }
}
