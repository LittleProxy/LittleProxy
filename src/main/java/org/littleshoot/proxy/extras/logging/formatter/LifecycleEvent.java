package org.littleshoot.proxy.extras.logging.formatter;

/**
 * Enumeration of lifecycle events that can occur during HTTP proxy processing. These events are
 * used for structured logging of connection and request lifecycle at DEBUG and TRACE levels.
 */
public enum LifecycleEvent {
  /** Client established TCP connection to proxy */
  CLIENT_CONNECTED("client_connected"),

  /** Client closed TCP connection to proxy */
  CLIENT_DISCONNECTED("client_disconnected"),

  /** SSL handshake completed successfully with client */
  CLIENT_SSL_HANDSHAKE_SUCCEEDED("client_ssl_handshake_succeeded"),

  /** Proxy established TCP connection to upstream server */
  SERVER_CONNECTED("server_connected"),

  /** Proxy closed TCP connection to upstream server */
  SERVER_DISCONNECTED("server_disconnected"),

  /** Connection became non-writable (backpressure) */
  CONNECTION_SATURATED("connection_saturated"),

  /** Connection became writable again after saturation */
  CONNECTION_WRITABLE("connection_writable"),

  /** Connection idle timeout triggered */
  CONNECTION_TIMED_OUT("connection_timed_out"),

  /** Exception caught on connection */
  CONNECTION_EXCEPTION_CAUGHT("connection_exception_caught"),

  /** HTTP request received from client */
  REQUEST_RECEIVED("request_received"),

  /** HTTP response sent to client */
  RESPONSE_SENT("response_sent");

  private final String eventName;

  LifecycleEvent(String eventName) {
    this.eventName = eventName;
  }

  /**
   * Returns the event name used in formatted output.
   *
   * @return the event name string
   */
  public String getEventName() {
    return eventName;
  }
}
