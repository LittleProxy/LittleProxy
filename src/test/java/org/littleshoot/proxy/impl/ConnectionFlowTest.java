package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for review item: a connection-flow attempt that has already settled must not
 * start another of its steps. The step that was in flight when the attempt failed can still
 * complete successfully afterwards; without an atomic "still live?" check, {@link
 * ConnectionFlow#advance()} would poll and execute the next step of the dead attempt, even though a
 * retry has already replaced it.
 */
final class ConnectionFlowTest {

  private final ClientToProxyConnection clientConnection = mock();
  private final ProxyToServerConnection serverConnection = mock();
  private final ProxyConnectionLogger logger = mock();

  @SuppressWarnings("unchecked")
  private final ProxyConnection<HttpResponse> stepConnection = mock(ProxyConnection.class);

  private final EmbeddedChannel channel = new EmbeddedChannel();

  @AfterEach
  void releaseChannel() {
    channel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("a step that succeeds after the flow failed does not start the next step")
  void successAfterFailureDoesNotAdvance() {
    when(serverConnection.getLOG()).thenReturn(logger);
    when(stepConnection.getLOG()).thenReturn(logger);
    // fail() disconnects before reporting to the client. Leave that future pending: this test only
    // cares that settlement has happened before the step's success is delivered.
    when(serverConnection.disconnect()).thenReturn(channel.newPromise());

    RecordingStep first = new RecordingStep(stepConnection, channel.newPromise());
    RecordingStep second = new RecordingStep(stepConnection, channel.newPromise());
    ConnectionFlow flow = new ConnectionFlow(clientConnection, serverConnection, new Object());
    flow.then(first).then(second);

    flow.start();
    assertThat(first.executeCount()).isEqualTo(1);

    flow.fail(new IllegalStateException("upstream closed"));

    // The in-flight step's own future completes successfully after the flow settled.
    first.succeed();
    assertThat(second.executeCount()).as("a settled flow must not execute another step").isZero();
  }

  /** A step that records how often it was started and completes its future on demand. */
  private static final class RecordingStep extends ConnectionFlowStep<HttpResponse> {
    private final ChannelPromise executeResult;
    private final AtomicInteger executeCount = new AtomicInteger();

    RecordingStep(ProxyConnection<HttpResponse> connection, ChannelPromise executeResult) {
      super(connection, ConnectionState.CONNECTING);
      this.executeResult = executeResult;
    }

    @Override
    boolean shouldExecuteOnEventLoop() {
      return false;
    }

    @Override
    protected Future<?> execute() {
      executeCount.incrementAndGet();
      return executeResult;
    }

    int executeCount() {
      return executeCount.get();
    }

    void succeed() {
      executeResult.setSuccess();
    }
  }
}
