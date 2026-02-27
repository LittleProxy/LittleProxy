package org.littleshoot.proxy.haproxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ChainedProxyAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * With {@code sendProxyProtocol=true} and an HTTP CONNECT chained proxy, the PROXY header must be
 * tunnelled to the final server (first bytes after the CONNECT handshake), not sent to the
 * intermediate proxy ahead of the CONNECT.
 *
 * <p>Topology: raw client → downstream LittleProxy → intermediate HTTP CONNECT proxy → final
 * server.
 */
@Tag("slow-test")
public final class ProxyProtocolHttpConnectChainedProxyTest {

  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private HttpProxyServer downstreamProxy;
  private Socket clientSocket;

  private final AtomicReference<String> intermediateFirstBytes = new AtomicReference<>();
  private final AtomicReference<String> finalServerFirstBytes = new AtomicReference<>();
  private final CountDownLatch intermediateReceivedConnect = new CountDownLatch(1);
  private final CountDownLatch finalServerReceivedData = new CountDownLatch(1);

  @AfterEach
  void tearDown() throws Exception {
    if (clientSocket != null) {
      clientSocket.close();
    }
    if (downstreamProxy != null) {
      downstreamProxy.abort();
    }
    if (bossGroup != null) {
      bossGroup.shutdownGracefully();
    }
    if (workerGroup != null) {
      workerGroup.shutdownGracefully();
    }
  }

  @Test
  void proxyProtocolHeaderIsTunnelledToFinalServerNotIntermediateProxy() throws Exception {
    bossGroup = new NioEventLoopGroup(1);
    workerGroup = new NioEventLoopGroup();

    int finalServerPort = startFinalServer();
    int intermediatePort = startIntermediateConnectProxy(finalServerPort);
    int downstreamPort = startDownstreamProxy(intermediatePort);

    sendConnectThroughDownstream(downstreamPort, finalServerPort);

    assertThat(intermediateReceivedConnect.await(5, TimeUnit.SECONDS))
        .as("intermediate proxy should receive the CONNECT")
        .isTrue();
    assertThat(finalServerReceivedData.await(5, TimeUnit.SECONDS))
        .as("final server should receive tunnelled bytes")
        .isTrue();

    assertThat(intermediateFirstBytes.get())
        .as("intermediate HTTP proxy must see a plain CONNECT as its first bytes")
        .startsWith("CONNECT");
    assertThat(intermediateFirstBytes.get())
        .as("intermediate HTTP proxy must NOT receive a PROXY header")
        .doesNotContain("PROXY TCP");

    assertThat(finalServerFirstBytes.get())
        .as("final server must receive the PROXY protocol header as the first tunnelled bytes")
        .startsWith("PROXY TCP");
  }

  /** Captures the first bytes the ultimate destination receives. */
  private int startFinalServer() {
    ServerBootstrap b = new ServerBootstrap();
    b.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                ch.pipeline()
                    .addLast(
                        new ChannelInboundHandlerAdapter() {
                          @Override
                          public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ByteBuf buf = (ByteBuf) msg;
                            finalServerFirstBytes.compareAndSet(
                                null, buf.toString(StandardCharsets.US_ASCII));
                            buf.release();
                            finalServerReceivedData.countDown();
                          }
                        });
              }
            });
    Channel ch = b.bind(0).syncUninterruptibly().channel();
    return ((InetSocketAddress) ch.localAddress()).getPort();
  }

  /**
   * Minimal HTTP CONNECT proxy: records its first bytes, answers 200, then relays to the final
   * server.
   */
  private int startIntermediateConnectProxy(int finalServerPort) {
    ServerBootstrap b = new ServerBootstrap();
    b.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                ch.pipeline()
                    .addLast(
                        new ChannelInboundHandlerAdapter() {
                          private volatile Channel outbound;
                          private boolean connectHandled;

                          @Override
                          public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ByteBuf buf = (ByteBuf) msg;
                            if (!connectHandled) {
                              connectHandled = true;
                              intermediateFirstBytes.compareAndSet(
                                  null, buf.toString(StandardCharsets.US_ASCII));
                              buf.release();
                              intermediateReceivedConnect.countDown();
                              connectToFinalServerThenRespond(ctx, finalServerPort);
                            } else if (outbound != null) {
                              outbound.writeAndFlush(msg);
                            } else {
                              buf.release();
                            }
                          }

                          private void connectToFinalServerThenRespond(
                              ChannelHandlerContext ctx, int port) {
                            Bootstrap cb = new Bootstrap();
                            cb.group(workerGroup)
                                .channel(NioSocketChannel.class)
                                .handler(
                                    new ChannelInboundHandlerAdapter() {
                                      @Override
                                      public void channelRead(ChannelHandlerContext c, Object m) {
                                        ReferenceCountUtil.release(m);
                                      }
                                    });
                            cb.connect("localhost", port)
                                .addListener(
                                    (ChannelFutureListener)
                                        f -> {
                                          if (f.isSuccess()) {
                                            outbound = f.channel();
                                            ctx.writeAndFlush(
                                                Unpooled.copiedBuffer(
                                                    "HTTP/1.1 200 Connection Established\r\n\r\n",
                                                    StandardCharsets.US_ASCII));
                                          } else {
                                            ctx.close();
                                          }
                                        });
                          }
                        });
              }
            });
    Channel ch = b.bind(0).syncUninterruptibly().channel();
    return ((InetSocketAddress) ch.localAddress()).getPort();
  }

  private int startDownstreamProxy(int intermediatePort) {
    downstreamProxy =
        DefaultHttpProxyServer.bootstrap()
            .withName("Downstream")
            .withPort(0)
            .withSendProxyProtocol(true)
            .withChainProxyManager(
                (httpRequest, chainedProxies, clientDetails) ->
                    chainedProxies.add(
                        new ChainedProxyAdapter() {
                          @Override
                          public InetSocketAddress getChainedProxyAddress() {
                            return new InetSocketAddress("localhost", intermediatePort);
                          }
                        }))
            .start();
    return downstreamProxy.getListenAddress().getPort();
  }

  private void sendConnectThroughDownstream(int downstreamPort, int finalServerPort)
      throws Exception {
    clientSocket = new Socket("localhost", downstreamPort);
    clientSocket.setSoTimeout(5000);
    OutputStream out = clientSocket.getOutputStream();
    String connect =
        "CONNECT localhost:"
            + finalServerPort
            + " HTTP/1.1\r\nHost: localhost:"
            + finalServerPort
            + "\r\n\r\n";
    out.write(connect.getBytes(StandardCharsets.US_ASCII));
    out.flush();
    // Discard the CONNECT response; the socket stays open until tearDown so the tunnel lives while
    // the PROXY header propagates.
    clientSocket.getInputStream().read(new byte[1024]);
  }
}
