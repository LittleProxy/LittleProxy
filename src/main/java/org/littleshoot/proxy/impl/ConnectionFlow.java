package org.littleshoot.proxy.impl;

import io.netty.util.concurrent.Future;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Coordinates the various steps involved in establishing a connection, such as establishing a
 * socket connection, SSL handshaking, HTTP CONNECT request processing, and so on.
 */
class ConnectionFlow {
  private final Deque<ConnectionFlowStep<?>> steps = new ConcurrentLinkedDeque<>();

  private final ClientToProxyConnection clientConnection;
  private final ProxyToServerConnection serverConnection;
  private volatile ConnectionFlowStep<?> currentStep;
  private volatile boolean suppressInitialRequest;
  private final Object connectLock;

  /**
   * Set once this flow has reported success or failure to its client. A single attempt can be
   * reported more than once: an idle timeout ({@code timedOut()}), a stale step-failure listener
   * and an upstream close can all call {@link #fail(Throwable)} for the same attempt. Reporting
   * twice would notify {@code serverConnectionFailed}/{@code serverConnectionSucceeded} twice,
   * unbalancing the client's read gate and possibly starting a second retry on top of the first.
   *
   * <p>Guarded by {@link #settlementLock}.
   */
  private boolean settled;

  /**
   * Guards the transition from "this attempt is still live" to "this attempt has settled", and the
   * decision of which step to run next. {@link #advance()} holds it while it checks {@link
   * #settled} and polls the next step, and {@link #succeed()}/{@link #fail(Throwable)} hold it
   * while they claim settlement. An {@code advance()} that races a settlement therefore either
   * wins, and the step it picked belongs to the live attempt, or loses and starts nothing. It is
   * never held while a step is executed or a callback into user code runs, and it is distinct from
   * {@code connectLock}, so claiming settlement can never wait on the connection's write ordering.
   */
  private final Object settlementLock = new Object();

  /**
   * Construct a new {@link ConnectionFlow} for the given client and server connections.
   *
   * @param clientConnection
   * @param serverConnection
   * @param connectLock an object shared by {@link ConnectionFlow} and {@link
   *     ProxyToServerConnection} that serializes connection-flow step completion, {@link
   *     ProxyToServerConnection#connectionSucceeded(ClientToProxyConnection, boolean)} and deferred
   *     writes. It is a mutual exclusion lock, not a wait/notify monitor.
   */
  ConnectionFlow(
      ClientToProxyConnection clientConnection,
      ProxyToServerConnection serverConnection,
      Object connectLock) {
    super();
    this.clientConnection = clientConnection;
    this.serverConnection = serverConnection;
    this.connectLock = connectLock;
  }

  /** Add a {@link ConnectionFlowStep} to the beginning of this flow. */
  ConnectionFlow first(ConnectionFlowStep<?> step) {
    steps.addFirst(step);
    return this;
  }

  /** Add a {@link ConnectionFlowStep} to the end of this flow. */
  ConnectionFlow then(ConnectionFlowStep<?> step) {
    steps.addLast(step);
    return this;
  }

  /**
   * While we're in the process of connecting, any messages read by the {@link
   * ProxyToServerConnection} are passed to this method, which passes it on to {@link
   * ConnectionFlowStep#read(ConnectionFlow, Object)} for the current {@link ConnectionFlowStep}.
   */
  void read(Object msg) {
    if (currentStep != null) {
      currentStep.read(this, msg);
    }
  }

  /**
   * Starts the connection flow, notifying the {@link ClientToProxyConnection} that we've started.
   */
  void start() {
    clientConnection.serverConnectionFlowStarted(serverConnection);
    advance();
  }

  /**
   * Advances the flow. {@link #advance()} will be called until we're either out of steps, or a step
   * has failed.
   *
   * <p>The "is this attempt still live?" check and the next-step poll happen atomically with
   * respect to {@link #fail(Throwable)}'s settlement claim (see {@link #settlementLock}), so a step
   * that completes after the attempt failed or succeeded cannot start another step of the dead
   * attempt. The step itself is then run outside the lock.
   */
  void advance() {
    final ConnectionFlowStep<?> nextStep;
    synchronized (settlementLock) {
      if (settled) {
        serverConnection.getLOG().debug("Ignoring advance of a settled connection flow");
        return;
      }
      nextStep = steps.poll();
      currentStep = nextStep;
    }
    if (nextStep == null) {
      succeed();
    } else {
      processCurrentStep(nextStep);
    }
  }

  /**
   * Process the given {@link ConnectionFlowStep}. With each step, we:
   *
   * <ol>
   *   <li>Change the state of the associated {@link ProxyConnection} to the value of {@link
   *       ConnectionFlowStep#getState()}
   *   <li>Call {@link ConnectionFlowStep#execute()}
   *   <li>On completion of the {@link Future} returned by {@link ConnectionFlowStep#execute()},
   *       check the success.
   *   <li>If successful, we call back into {@link ConnectionFlowStep#onSuccess(ConnectionFlow)}.
   *   <li>If unsuccessful, we call {@link #fail()}, stopping the connection flow
   * </ol>
   */
  private void processCurrentStep(ConnectionFlowStep<?> step) {
    final ProxyConnection<?> connection = step.getConnection();
    final ProxyConnectionLogger LOG = connection.getLOG();

    if (isSettled()) {
      LOG.debug("Skipping step of a settled connection flow: {}", step);
      return;
    }
    LOG.debug("Processing connection flow step: {}", step);
    connection.become(step.getState());
    suppressInitialRequest = suppressInitialRequest || step.shouldSuppressInitialRequest();

    if (step.shouldExecuteOnEventLoop()) {
      connection.ctx.executor().submit(() -> doProcessCurrentStep(step, LOG));
    } else {
      doProcessCurrentStep(step, LOG);
    }
  }

  /**
   * Does the work of processing the given step, checking the result and handling success/failure.
   *
   * <p>Re-checks {@link #settled}: for a step submitted to the event loop, the attempt can fail
   * between the submission and this task running, and the step must not then act on a connection
   * that a retry may already have rebound to a new channel.
   */
  private void doProcessCurrentStep(
      final ConnectionFlowStep<?> step, final ProxyConnectionLogger LOG) {
    if (isSettled()) {
      LOG.debug("Skipping step of a settled connection flow: {}", step);
      return;
    }
    step.execute()
        .addListener(
            future -> {
              if (future.isSuccess()) {
                LOG.debug("ConnectionFlowStep succeeded");
                // Deliberately not under connectLock: onSuccess() normally advances straight into
                // succeed(), and the resulting connectionSucceeded() runs user callbacks
                // (ChainedProxy, HttpFilters) that must not be able to stall write() callers. The
                // ordering-critical part takes connectLock itself, inside connectionSucceeded().
                step.onSuccess(ConnectionFlow.this);
              } else {
                LOG.debug("ConnectionFlowStep failed", future.cause());
                synchronized (connectLock) {
                  fail(future.cause());
                }
              }
            });
  }

  /**
   * Called when the flow is complete and successful. Notifies the {@link ProxyToServerConnection}
   * that we succeeded.
   *
   * <p>The success is reported to {@code clientConnection}, the client that started this flow and
   * was told so by {@link #start()}. A pooled connection can meanwhile have been borrowed by
   * another client, whose writes are only queued on it while it is still connecting; notifying that
   * other client would decrement a connecting count it never incremented and leave both clients
   * with reading paused.
   */
  void succeed() {
    if (!settle()) {
      serverConnection.getLOG().debug("Ignoring repeated completion of connection flow");
      return;
    }
    serverConnection.getLOG().debug("Connection flow completed successfully: {}", currentStep);
    serverConnection.connectionSucceeded(clientConnection, !suppressInitialRequest);
  }

  /**
   * Claims the single failure settlement of this attempt so that the caller can report the failure
   * itself, as {@link ProxyToServerConnection#exceptionCaught(Throwable)} does.
   *
   * <p>One attempt can produce more than one failure notification: a socket read can fail with an
   * {@link java.io.IOException} and the {@link io.netty.handler.ssl.SslHandler} can then report the
   * same close, and the in-flight step's future can fail as well. If every notification reported
   * the failure, the client's read gate would be decremented more than once and the connection
   * could be retried a second time on top of the retry the first notification already started.
   * Claiming the settlement here makes the later notifications no-ops.
   *
   * @return {@code true} if this attempt was still live and is now claimed by the caller, {@code
   *     false} if it had already been settled and its outcome reported
   */
  boolean settleFailure() {
    return settle();
  }

  /** Claims the single settlement of this attempt, returning {@code false} if it was claimed. */
  private boolean settle() {
    synchronized (settlementLock) {
      if (settled) {
        return false;
      }
      settled = true;
      return true;
    }
  }

  /** Whether this attempt has already been reported as succeeded or failed. */
  private boolean isSettled() {
    synchronized (settlementLock) {
      return settled;
    }
  }

  /**
   * Called when the flow fails at some {@link ConnectionFlowStep}. Disconnects the {@link
   * ProxyToServerConnection} and informs the {@link ClientToProxyConnection} that our connection
   * failed.
   */
  void fail(final Throwable cause) {
    if (!settle()) {
      // Already reported for this attempt: a stale close notification, or the idle timeout racing
      // the failing step's own future. Reporting again would tell the client twice (unbalancing its
      // read gate) and could start a second retry while the first one is already connecting.
      serverConnection.getLOG().debug("Ignoring repeated failure of connection flow", cause);
      return;
    }
    final ConnectionState lastStateBeforeFailure = serverConnection.getCurrentState();
    serverConnection
        .disconnect()
        .addListener(
            future -> {
              synchronized (connectLock) {
                if (!clientConnection.serverConnectionFailed(
                    serverConnection, lastStateBeforeFailure, cause)) {
                  // the connection to the server failed, and we are not retrying, so transition to
                  // the DISCONNECTED state
                  serverConnection.become(ConnectionState.DISCONNECTED);
                }
              }
            });
  }

  /** Like {@link #fail(Throwable)} but with no cause. */
  void fail() {
    fail(null);
  }
}
