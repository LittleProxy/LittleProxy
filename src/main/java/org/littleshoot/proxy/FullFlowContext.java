package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import java.util.Objects;
import org.littleshoot.proxy.impl.ClientToProxyConnection;
import org.littleshoot.proxy.impl.ProxyToServerConnection;

/**
 * Extension of {@link FlowContext} that provides additional information (which we know after
 * actually processing the request from the client).
 */
public class FullFlowContext extends FlowContext {
  private final String serverHostAndPort;
  private final ChainedProxy chainedProxy;
  private final ChannelHandlerContext ctx;
  private final String serverConnectionId;

  public FullFlowContext(
      ClientToProxyConnection clientConnection, ProxyToServerConnection serverConnection) {
    super(clientConnection);
    serverHostAndPort = serverConnection.getServerHostAndPort();
    chainedProxy = serverConnection.getChainedProxy();
    this.ctx = serverConnection.getContext();
    this.serverConnectionId = serverConnection.getId();
  }

  @Override
  public String getFlowId() {
    return super.getFlowId() + '.' + serverConnectionId;
  }

  /** The host and port for the server (i.e. the ultimate endpoint). */
  public String getServerHostAndPort() {
    return serverHostAndPort;
  }

  /** The chained proxy (if proxy chaining). */
  public ChainedProxy getChainedProxy() {
    return chainedProxy;
  }

  /** The proxy to server channel context. */
  public ChannelHandlerContext getProxyToServerContext() {
    return ctx;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    if (!super.equals(o)) return false;
    FullFlowContext that = (FullFlowContext) o;
    return Objects.equals(getFlowId(), that.getFlowId());
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), Objects.hash(getFlowId()));
  }
}
