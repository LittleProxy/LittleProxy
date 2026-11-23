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
   * Guards the transition from "this attempt is still live" to "this attempt has settled", the
   * choice of the next step, and the reservation a step makes before it starts. {@link #advance()}
   * holds it while it checks {@link #settled} and polls the next step, and {@link
   * #succeed()}/{@link #fail(Throwable)} hold it while they claim settlement. It is held only for
   * short bookkeeping sections, never while a step is executed or a callback into user code runs,
   * and it is distinct from {@code connectLock}, so claiming settlement can never wait on the
   * connection's write ordering.
   */
  private final Object settlementLock = new Object();

  /**
   * True while a step has passed the settlement check and its {@link ConnectionFlowStep#execute()}
   * has not returned yet. Guarded by {@link #settlementLock}. A settlement claimed in that window
   * is deferred instead of tearing the connection down: the step has already been admitted and must
   * act on the attempt it started for, not on one that a retry may have rebound in the meantime.
   */
  private boolean stepStarting;

  /**
   * Settlement claimed while a step was starting, to be run once {@code execute()} returns. Guarded
   * by {@link #settlementLock}. It runs on the stepping thread before the step's future listener is
   * registered.
   */
  private Runnable deferredSettlement;

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

    if (step.shouldExecuteOnEventLoop()) {
      connection.ctx.executor().submit(() -> doProcessCurrentStep(step, LOG));
    } else {
      doProcessCurrentStep(step, LOG);
    }
  }

  /**
   * Does the work of processing the given step, checking the result and handling success/failure.
   *
   * <p>The settlement check, the state transition and the reservation of the step happen atomically
   * under {@link #settlementLock}. A settlement claimed after that reservation is deferred until
   * {@code execute()} returns (see {@link #deferredSettlement}), so the step cannot act on a
   * connection that a retry has rebound, and a step that arrives after the claim neither starts nor
   * changes the connection's state. No lock is held while {@code execute()} runs.
   */
  private void doProcessCurrentStep(
      final ConnectionFlowStep<?> step, final ProxyConnectionLogger LOG) {
    final ProxyConnection<?> connection = step.getConnection();
    synchronized (settlementLock) {
      if (settled) {
        LOG.debug("Skipping step of a settled connection flow: {}", step);
        return;
      }
      connection.become(step.getState());
      suppressInitialRequest = suppressInitialRequest || step.shouldSuppressInitialRequest();
      stepStarting = true;
    }

    final Future<?> stepFuture;
    try {
      stepFuture = step.execute();
    } finally {
      final Runnable settlement;
      synchronized (settlementLock) {
        stepStarting = false;
        settlement = deferredSettlement;
        deferredSettlement = null;
      }
      if (settlement != null) {
        settlement.run();
      }
    }
    stepFuture.addListener(
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
    boolean claimed =
        claimSettlement(
            () -> {
              serverConnection
                  .getLOG()
                  .debug("Connection flow completed successfully: {}", currentStep);
              serverConnection.connectionSucceeded(clientConnection, !suppressInitialRequest);
            });
    if (!claimed) {
      serverConnection.getLOG().debug("Ignoring repeated completion of connection flow");
    }
  }

  /**
   * Claims the single settlement of this attempt and runs {@code settlement}, or defers it until
   * the step that is currently starting returns (see {@link #deferredSettlement}).
   *
   * <p>Claiming and the reservation in {@link #doProcessCurrentStep} are mutually exclusive under
   * {@link #settlementLock}, so once settlement has been claimed no further step starts. A step
   * that had already been admitted is allowed to run to its first suspension point before the
   * settlement disconnects and retries the connection, so it acts on the attempt it was admitted
   * for rather than on a rebound one.
   *
   * @return {@code false} if the attempt had already been settled
   */
  private boolean claimSettlement(Runnable settlement) {
    synchronized (settlementLock) {
      if (settled) {
        return false;
      }
      settled = true;
      if (stepStarting) {
        deferredSettlement = settlement;
        return true;
      }
    }
    settlement.run();
    return true;
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
    final ConnectionState lastStateBeforeFailure = serverConnection.getCurrentState();
    boolean claimed =
        claimSettlement(
            () ->
                serverConnection
                    .disconnect()
                    .addListener(
                        future -> {
                          synchronized (connectLock) {
                            if (!clientConnection.serverConnectionFailed(
                                serverConnection, lastStateBeforeFailure, cause)) {
                              // the connection to the server failed, and we are not retrying, so
                              // transition to the DISCONNECTED state
                              serverConnection.become(ConnectionState.DISCONNECTED);
                            }
                          }
                        }));
    if (!claimed) {
      // Already reported for this attempt: a stale close notification, or the idle timeout racing
      // the failing step's own future. Reporting again would tell the client twice (unbalancing its
      // read gate) and could start a second retry while the first one is already connecting.
      serverConnection.getLOG().debug("Ignoring repeated failure of connection flow", cause);
    }
  }

  /** Like {@link #fail(Throwable)} but with no cause. */
  void fail() {
    fail(null);
  }
}
