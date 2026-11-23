package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the settlement of a connection-flow attempt: once an attempt has settled
 * (succeeded or failed), it must not report again or start another of its steps. The step that was
 * in flight when the attempt failed can still complete successfully afterwards; without an atomic
 * "still live?" check, {@link ConnectionFlow#advance()} would poll and execute the next step of the
 * dead attempt, even though a retry has already replaced it.
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

  @Test
  @DisplayName("a step submitted before the flow failed does not run after it")
  void submittedStepIsSkippedWhenFlowSettlesFirst() {
    when(serverConnection.getLOG()).thenReturn(logger);
    when(stepConnection.getLOG()).thenReturn(logger);
    when(serverConnection.disconnect()).thenReturn(channel.newPromise());

    // A real event loop that does not run the task until this test tells it to.
    ChannelHandlerContext ctx = mock();
    EventExecutor executor = mock();
    AtomicReference<Runnable> submitted = new AtomicReference<>();
    when(ctx.executor()).thenReturn(executor);
    doAnswer(
            invocation -> {
              submitted.set(invocation.getArgument(0));
              return null;
            })
        .when(executor)
        .submit(any(Runnable.class));
    stepConnection.ctx = ctx;

    RecordingStep step = new RecordingStep(stepConnection, channel.newPromise(), true);
    ConnectionFlow flow = new ConnectionFlow(clientConnection, serverConnection, new Object());
    flow.then(step);

    flow.start();
    assertThat(step.executeCount()).isZero();
    Runnable submittedTask = submitted.get();
    assertThat(submittedTask).as("the step must be submitted to the event loop").isNotNull();

    // The attempt fails before the event loop gets to run the submitted task.
    flow.fail(new IllegalStateException("upstream closed"));
    submittedTask.run();

    assertThat(step.executeCount())
        .as("a step submitted before settlement must not run after it")
        .isZero();
  }

  @Test
  @DisplayName("a step is not started when the flow settles while the step is being dispatched")
  void settledFlowDoesNotStartADispatchedStep() {
    when(serverConnection.getLOG()).thenReturn(logger);
    when(serverConnection.disconnect()).thenReturn(channel.newPromise());
    RecordingStep step = new RecordingStep(serverConnection, channel.newPromise());
    ConnectionFlow flow = new ConnectionFlow(clientConnection, serverConnection, new Object());
    flow.then(step);
    // advance() polls the step and then calls processCurrentStep(), which re-checks settlement
    // before executing. Settle the flow from the logger lookup that happens between those two
    // points, so the guard is exercised deterministically instead of relying on a race.
    when(serverConnection.getLOG())
        .thenAnswer(
            invocation -> {
              flow.fail(new IllegalStateException("settled while dispatching"));
              return logger;
            });

    flow.advance();

    assertThat(step.executeCount())
        .as("a step must not run after the flow settled during its dispatch")
        .isZero();
  }

  @Test
  @DisplayName("a settlement waits for a step that is starting")
  void settlementWaitsForTheStepThatIsStarting() throws Exception {
    when(serverConnection.getLOG()).thenReturn(logger);
    when(stepConnection.getLOG()).thenReturn(logger);
    when(serverConnection.disconnect()).thenReturn(channel.newPromise());

    CountDownLatch executeStarted = new CountDownLatch(1);
    CountDownLatch releaseExecute = new CountDownLatch(1);
    RecordingStep step =
        new RecordingStep(stepConnection, channel.newPromise()) {
          @Override
          protected Future<?> execute() {
            executeStarted.countDown();
            try {
              releaseExecute.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("interrupted while starting the step", e);
            }
            return super.execute();
          }
        };
    ConnectionFlow flow = new ConnectionFlow(clientConnection, serverConnection, new Object());
    flow.then(step);

    Thread dispatcher = new Thread(flow::start, "flow-step-dispatcher");
    dispatcher.start();
    assertThat(executeStarted.await(5, TimeUnit.SECONDS)).as("the step must have started").isTrue();

    Thread settler =
        new Thread(() -> flow.fail(new IllegalStateException("settled")), "flow-settler");
    settler.start();
    awaitParked(settler);

    assertThat(settler.getState())
        .as("a settlement must not claim the attempt while a step is starting")
        .isEqualTo(Thread.State.WAITING);

    releaseExecute.countDown();
    dispatcher.join(5000);
    settler.join(5000);
    assertThat(settler.isAlive())
        .as("the settlement must complete after the step started")
        .isFalse();
    assertThat(step.executeCount()).isEqualTo(1);
  }

  /** Waits until the given thread parks (or dies), so its state can be asserted exactly. */
  private static void awaitParked(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.isAlive()
        && thread.getState() != Thread.State.WAITING
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
  }

  @Test
  @DisplayName("a flow with no steps succeeds once and ignores later settlement")
  void emptyFlowSettlesOnce() {
    when(serverConnection.getLOG()).thenReturn(logger);
    ConnectionFlow flow = new ConnectionFlow(clientConnection, serverConnection, new Object());

    flow.start();
    // An idle timeout or a close can report after the flow already succeeded; both must be no-ops.
    flow.succeed();
    flow.fail(new IllegalStateException("late"));

    verify(serverConnection, times(1)).connectionSucceeded(clientConnection, true);
    verify(serverConnection, never()).disconnect();
  }

  @Test
  @DisplayName("a step that suppresses the initial request is reported as not forwarding it")
  void suppressedInitialRequestIsReported() {
    when(serverConnection.getLOG()).thenReturn(logger);
    when(stepConnection.getLOG()).thenReturn(logger);

    RecordingStep step =
        new RecordingStep(stepConnection, channel.newPromise()) {
          @Override
          boolean shouldSuppressInitialRequest() {
            return true;
          }
        };
    ConnectionFlow flow = new ConnectionFlow(clientConnection, serverConnection, new Object());
    flow.then(step);

    flow.start();
    step.succeed();

    verify(serverConnection).connectionSucceeded(clientConnection, false);
  }

  @Test
  @DisplayName("read is delegated to the current step and ignored before the flow starts")
  void readIsDelegatedToTheCurrentStep() {
    when(serverConnection.getLOG()).thenReturn(logger);
    when(stepConnection.getLOG()).thenReturn(logger);
    RecordingStep step = new RecordingStep(stepConnection, channel.newPromise());
    ConnectionFlow flow = new ConnectionFlow(clientConnection, serverConnection, new Object());
    flow.then(step);

    flow.read("before start");
    assertThat(step.readMessages()).isEmpty();

    flow.start();
    flow.read("while the step is pending");
    assertThat(step.readMessages()).containsExactly("while the step is pending");
  }

  /** A step that records how often it was started and completes its future on demand. */
  private static class RecordingStep extends ConnectionFlowStep<HttpResponse> {
    private final ChannelPromise executeResult;
    private final boolean executeOnEventLoop;
    private final AtomicInteger executeCount = new AtomicInteger();
    private final List<Object> readMessages = new ArrayList<>();

    RecordingStep(ProxyConnection<HttpResponse> connection, ChannelPromise executeResult) {
      this(connection, executeResult, false);
    }

    RecordingStep(
        ProxyConnection<HttpResponse> connection,
        ChannelPromise executeResult,
        boolean executeOnEventLoop) {
      super(connection, ConnectionState.CONNECTING);
      this.executeResult = executeResult;
      this.executeOnEventLoop = executeOnEventLoop;
    }

    @Override
    boolean shouldExecuteOnEventLoop() {
      return executeOnEventLoop;
    }

    @Override
    protected Future<?> execute() {
      executeCount.incrementAndGet();
      return executeResult;
    }

    @Override
    void read(ConnectionFlow flow, Object msg) {
      readMessages.add(msg);
    }

    int executeCount() {
      return executeCount.get();
    }

    List<Object> readMessages() {
      return readMessages;
    }

    void succeed() {
      executeResult.setSuccess();
    }
  }
}
