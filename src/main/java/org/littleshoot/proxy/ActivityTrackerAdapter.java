package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLSession;

/**
 * Adapter of {@link ActivityTracker} interface that provides default no-op implementations of all
 * methods.
 */
public class ActivityTrackerAdapter implements ActivityTracker {

  @Override
  public void bytesReceivedFromClient(FlowContext flowContext, int numberOfBytes) {}

  // Note: the 3-arg request/response variants with a requestId are NOT overridden here; the
  // interface's default implementations delegate to these 2-arg methods, so subclasses (old or
  // new style) keep receiving callbacks. Override the 3-arg variants in the interface instead
  // if you need the request identifier.
  @Override
  public void requestReceivedFromClient(FlowContext flowContext, HttpRequest httpRequest) {}

  @Override
  public void bytesSentToServer(FullFlowContext flowContext, int numberOfBytes) {}

  @Override
  public void requestSentToServer(FullFlowContext flowContext, HttpRequest httpRequest) {}

  @Override
  public void bytesReceivedFromServer(FullFlowContext flowContext, int numberOfBytes) {}

  @Override
  public void responseReceivedFromServer(FullFlowContext flowContext, HttpResponse httpResponse) {}

  @Override
  public void bytesSentToClient(FlowContext flowContext, int numberOfBytes) {}

  @Override
  public void responseSentToClient(FlowContext flowContext, HttpResponse httpResponse) {}

  @Override
  public void clientConnected(FlowContext flowContext) {}

  @Override
  public void clientSSLHandshakeStarted(FlowContext flowContext) {}

  @Override
  public void clientSSLHandshakeSucceeded(FlowContext flowContext, SSLSession sslSession) {}

  @Override
  public void clientDisconnected(FlowContext flowContext, SSLSession sslSession) {}

  @Override
  public void serverConnected(FullFlowContext flowContext, InetSocketAddress serverAddress) {}

  @Override
  public void serverDisconnected(FullFlowContext flowContext, InetSocketAddress serverAddress) {}

  @Override
  public void connectionSaturated(FlowContext flowContext) {}

  @Override
  public void connectionWritable(FlowContext flowContext) {}

  @Override
  public void connectionTimedOut(FlowContext flowContext) {}

  @Override
  public void connectionExceptionCaught(FlowContext flowContext, Throwable cause) {}
}
