package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLSession;

/**
 * Interface for receiving information about activity in the proxy.
 *
 * <p>Sub-classes may wish to extend {@link ActivityTrackerAdapter} for sensible defaults.
 */
public interface ActivityTracker {

  /** Record that a client connected. */
  void clientConnected(FlowContext flowContext);

  /** Record that a client's SSL handshake completed. */
  void clientSSLHandshakeSucceeded(FlowContext flowContext, SSLSession sslSession);

  /** Record that a client disconnected. */
  void clientDisconnected(FlowContext flowContext, SSLSession sslSession);

  /**
   * Record that the proxy received bytes from the client.
   *
   * @param flowContext if full information is available, this will be a {@link FullFlowContext}.
   * @param numberOfBytes
   */
  void bytesReceivedFromClient(FlowContext flowContext, int numberOfBytes);

  /**
   * Record that proxy received an {@link HttpRequest} from the client.
   *
   * <p>Note - on chunked transfers, this is only called once (for the initial HttpRequest object).
   *
   * @param flowContext if full information is available, this will be a {@link FullFlowContext}.
   * @param httpRequest
   */
  void requestReceivedFromClient(FlowContext flowContext, HttpRequest httpRequest);

  /**
   * Record that the proxy attempted to send bytes to the server.
   *
   * @param flowContext provides contextual information about the flow
   * @param numberOfBytes
   */
  void bytesSentToServer(FullFlowContext flowContext, int numberOfBytes);

  /**
   * Record that proxy attempted to send a request to the server.
   *
   * <p>Note - on chunked transfers, this is only called once (for the initial HttpRequest object).
   *
   * @param flowContext provides contextual information about the flow
   * @param httpRequest
   */
  void requestSentToServer(FullFlowContext flowContext, HttpRequest httpRequest);

  /**
   * Record that the proxy received bytes from the server.
   *
   * @param flowContext provides contextual information about the flow
   * @param numberOfBytes
   */
  void bytesReceivedFromServer(FullFlowContext flowContext, int numberOfBytes);

  /**
   * Record that the proxy received an {@link HttpResponse} from the server.
   *
   * <p>Note - on chunked transfers, this is only called once (for the initial HttpRequest object).
   *
   * @param flowContext provides contextual information about the flow
   * @param httpResponse
   */
  void responseReceivedFromServer(FullFlowContext flowContext, HttpResponse httpResponse);

  /**
   * Record that the proxy sent bytes to the client.
   *
   * @param flowContext if full information is available, this will be a {@link FullFlowContext}.
   * @param numberOfBytes
   */
  void bytesSentToClient(FlowContext flowContext, int numberOfBytes);

  /**
   * Record that the proxy sent a response to the client.
   *
   * <p>Note - on chunked transfers, this is only called once (for the initial HttpRequest object).
   *
   * @param flowContext if full information is available, this will be a {@link FullFlowContext}.
   * @param httpResponse
   */
  void responseSentToClient(FlowContext flowContext, HttpResponse httpResponse);

  /**
   * Record that the proxy connected to the server.
   *
   * @param flowContext provides contextual information about the flow
   * @param serverAddress the address of the server that was connected
   */
  void serverConnected(FullFlowContext flowContext, InetSocketAddress serverAddress);

  /**
   * Record that the proxy disconnected from the server.
   *
   * @param flowContext provides contextual information about the flow
   * @param serverAddress the address of the server that was disconnected
   */
  void serverDisconnected(FullFlowContext flowContext, InetSocketAddress serverAddress);

  /**
   * Record that a connection became saturated (not writable).
   *
   * @param flowContext if full information is available, this will be a {@link FullFlowContext}.
   */
  void connectionSaturated(FlowContext flowContext);

  /**
   * Record that a connection became writable again after being saturated.
   *
   * @param flowContext if full information is available, this will be a {@link FullFlowContext}.
   */
  void connectionWritable(FlowContext flowContext);

  /**
   * Record that a connection timed out due to idle timeout.
   *
   * @param flowContext if full information is available, this will be a {@link FullFlowContext}.
   */
  void connectionTimedOut(FlowContext flowContext);

  /**
   * Record that an exception was caught on a connection.
   *
   * @param flowContext if full information is available, this will be a {@link FullFlowContext}.
   * @param cause the exception that was caught
   */
  void connectionExceptionCaught(FlowContext flowContext, Throwable cause);
}
