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
   * Construct a new {@link ConnectionFlow} for the given client and server connections.
   *
   * @param clientConnection
   * @param serverConnection
   * @param connectLock an object that's shared by {@link ConnectionFlow} and {@link
   *     ProxyToServerConnection} and that is used for synchronizing the reader and writer threads
   *     that are both involved during the establishing of a connection.
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
   */
  void advance() {
    currentStep = steps.poll();
    if (currentStep == null) {
      succeed();
    } else {
      processCurrentStep();
    }
  }

  /**
   * Process the current {@link ConnectionFlowStep}. With each step, we:
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
  private void processCurrentStep() {
    final ProxyConnection<?> connection = currentStep.getConnection();
    final ProxyConnectionLogger LOG = connection.getLOG();

    LOG.debug("Processing connection flow step: {}", currentStep);
    connection.become(currentStep.getState());
    suppressInitialRequest = suppressInitialRequest || currentStep.shouldSuppressInitialRequest();

    if (currentStep.shouldExecuteOnEventLoop()) {
      connection.ctx.executor().submit(() -> doProcessCurrentStep(LOG));
    } else {
      doProcessCurrentStep(LOG);
    }
  }

  /**
   * Does the work of processing the current step, checking the result and handling success/failure.
   */
  private void doProcessCurrentStep(final ProxyConnectionLogger LOG) {
    currentStep
        .execute()
        .addListener(
            future -> {
              synchronized (connectLock) {
                if (future.isSuccess()) {
                  LOG.debug("ConnectionFlowStep succeeded");
                  currentStep.onSuccess(ConnectionFlow.this);
                } else {
                  LOG.debug("ConnectionFlowStep failed", future.cause());
                  fail(future.cause());
                }
              }
            });
  }

  /**
   * Called when the flow is complete and successful. Notifies the {@link ProxyToServerConnection}
   * that we succeeded.
   */
  void succeed() {
    synchronized (connectLock) {
      serverConnection.getLOG().debug("Connection flow completed successfully: {}", currentStep);
      serverConnection.connectionSucceeded(!suppressInitialRequest);
      notifyThreadsWaitingForConnection();
    }
  }

  /**
   * Called when the flow fails at some {@link ConnectionFlowStep}. Disconnects the {@link
   * ProxyToServerConnection} and informs the {@link ClientToProxyConnection} that our connection
   * failed.
   */
  void fail(final Throwable cause) {
    final ConnectionState lastStateBeforeFailure = serverConnection.getCurrentState();
    serverConnection
        .disconnect()
        .addListener(
            future -> {
              synchronized (connectLock) {
                if (!clientConnection.serverConnectionFailed(
                    serverConnection, lastStateBeforeFailure, cause)) {
                  // the connection to the server failed, and we are not retrying, so transition to
                  // the
                  // DISCONNECTED state
                  serverConnection.become(ConnectionState.DISCONNECTED);

                  // We are not retrying our connection, let anyone waiting for a connection know
                  // that we're done
                  notifyThreadsWaitingForConnection();
                }
              }
            });
  }

  /** Like {@link #fail(Throwable)} but with no cause. */
  void fail() {
    fail(null);
  }

  /**
   * Once we've finished recording our connection and written our initial request, we can notify
   * anyone who is waiting on the connection that it's okay to proceed.
   */
  private void notifyThreadsWaitingForConnection() {
    connectLock.notifyAll();
  }
}
