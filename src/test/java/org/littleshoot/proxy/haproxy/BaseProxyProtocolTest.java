package org.littleshoot.proxy.haproxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.HttpProxyServerBootstrap;
import org.littleshoot.proxy.SslEngineSource;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Base for running Proxy protocol tests. Proxy Protocol tests need special client and servers that
 * are capable of emitting and consuming proxy protocol headers.
 *
 * <p>Subclasses may override {@link #useTlsInbound()} to enable TLS between the client and the
 * proxy. When TLS is enabled, the client sends the PROXY protocol header as cleartext
 * <em>before</em> the TLS handshake, matching real-world deployments (e.g. AWS NLB → proxy).
 *
 * <p>Subclasses may override {@link #sendProxyHeaderBeforeTls()} to control the ordering of the
 * PROXY header relative to the TLS handshake. Returning {@code false} causes the PROXY header to be
 * sent <em>inside</em> the TLS tunnel (after the handshake), which is useful for negative testing.
 */
public abstract class BaseProxyProtocolTest {

  private CountDownLatch serverHandlerReady;
  private EventLoopGroup childGroup;
  private EventLoopGroup parentGroup;
  private EventLoopGroup clientWorkGroup;
  private ProxyProtocolServerHandler proxyProtocolServerHandler;

  private HttpProxyServer proxyServer;
  private int proxyPort;
  private boolean acceptProxy = true;
  private boolean sendProxy = true;
  int serverPort;
  static final String SOURCE_ADDRESS = "192.168.0.153";
  static final String DESTINATION_ADDRESS = "192.168.0.154";
  static final String SOURCE_PORT = "123";
  static final String DESTINATION_PORT = "456";
  static final CountDownLatch clientTlsHandshakeDone = new CountDownLatch(1);
  private volatile ProxyProtocolClientHandler clientHandler;

  protected boolean useTlsInbound() {
    return false;
  }

  /** Controls whether the PROXY protocol header is sent before or after the TLS handshake. */
  protected boolean sendProxyHeaderBeforeTls() {
    return true;
  }

  boolean isClientTlsHandshakeSuccess() {
    return clientHandler != null && clientHandler.isTlsHandshakeSuccess();
  }

  Throwable getClientTlsHandshakeFailureCause() {
    return clientHandler != null ? clientHandler.getTlsHandshakeFailureCause() : null;
  }

  protected final void setup(boolean acceptProxy, boolean sendProxy) throws Exception {
    this.acceptProxy = acceptProxy;
    this.sendProxy = sendProxy;
    this.serverHandlerReady = new CountDownLatch(1);
    startProxyServer();
    startServer();
    startClient();
  }

  final void startServer() {
    parentGroup = new NioEventLoopGroup();
    childGroup = new NioEventLoopGroup();
    ServerBootstrap b = new ServerBootstrap();
    b.group(parentGroup, childGroup)
        .channelFactory(NioServerSocketChannel::new)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              public void initChannel(SocketChannel ch) {
                proxyProtocolServerHandler = new ProxyProtocolServerHandler();
                ch.pipeline()
                    .addLast(new HAProxyMessageDecoder())
                    .addLast(new HttpRequestDecoder())
                    .addLast(proxyProtocolServerHandler);
                serverHandlerReady.countDown();
              }
            })
        .option(ChannelOption.SO_BACKLOG, 128)
        .childOption(ChannelOption.SO_KEEPALIVE, true);

    ChannelFuture f = b.bind(0).awaitUninterruptibly();
    Throwable cause = f.cause();
    if (cause != null) {
      throw new RuntimeException(cause);
    }
    serverPort = ((InetSocketAddress) f.channel().localAddress()).getPort();
    Runtime.getRuntime().addShutdownHook(new Thread(this::stopServer, "stopServerHook"));
  }

  final void startClient() throws Exception {
    String host = "localhost";
    clientWorkGroup = new NioEventLoopGroup();
    Bootstrap b = new Bootstrap();
    b.group(clientWorkGroup);
    b.channel(NioSocketChannel.class);
    b.option(ChannelOption.SO_KEEPALIVE, true);
    b.handler(
        new ChannelInitializer<SocketChannel>() {
          @Override
          public void initChannel(SocketChannel ch) throws SSLException {
            ch.pipeline().addLast(new ReadTimeoutHandler(1));
            if (!useTlsInbound() && acceptProxy) {
              ch.pipeline().addLast(new ProxyProtocolTestEncoder());
            }

            // Build client SslContext for TLS cases.
            SslContext clientSslCtx = null;
            if (useTlsInbound()) {
              clientSslCtx =
                  SslContextBuilder.forClient()
                      .trustManager(InsecureTrustManagerFactory.INSTANCE)
                      .build();
            }

            clientHandler =
                new ProxyProtocolClientHandler(
                    serverPort,
                    getProxyProtocolHeader(),
                    clientSslCtx,
                    proxyPort,
                    clientTlsHandshakeDone,
                    sendProxyHeaderBeforeTls());

            ch.pipeline()
                .addLast(new HttpResponseDecoder())
                .addLast(new HttpRequestEncoder())
                .addLast(clientHandler);
          }
        });
    b.connect(host, proxyPort).sync();
  }

  HAProxyMessage getRelayedHaProxyMessage() throws InterruptedException {
    if (!serverHandlerReady.await(5, TimeUnit.SECONDS)) {
      return null;
    }
    return proxyProtocolServerHandler.awaitHaProxyMessage(3, TimeUnit.SECONDS);
  }

  private void stopServer() {
    childGroup.shutdownGracefully();
    parentGroup.shutdownGracefully();
  }

  private void stopProxyServer() {
    proxyServer.abort();
  }

  private void startProxyServer() throws CertificateException, SSLException {
    HttpProxyServerBootstrap builder =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withAcceptProxyProtocol(acceptProxy)
            .withSendProxyProtocol(sendProxy);

    if (useTlsInbound()) {
      SelfSignedCertificate ssc = new SelfSignedCertificate();
      SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();

      builder.withSslEngineSource(
          new SslEngineSource() {
            @Override
            public SSLEngine newSslEngine() {
              return sslCtx.newEngine(io.netty.buffer.ByteBufAllocator.DEFAULT);
            }

            @Override
            public SSLEngine newSslEngine(String peerHost, int peerPort) {
              return sslCtx.newEngine(io.netty.buffer.ByteBufAllocator.DEFAULT, peerHost, peerPort);
            }
          });

      builder.withAuthenticateSslClients(false);
    }

    proxyServer = builder.start();
    proxyPort = proxyServer.getListenAddress().getPort();
  }

  private ProxyProtocolHeader getProxyProtocolHeader() {
    return new ProxyProtocolHeader(
        SOURCE_ADDRESS, DESTINATION_ADDRESS, SOURCE_PORT, DESTINATION_PORT);
  }

  @AfterEach
  final void tearDown() {
    stopServer();
    stopProxyServer();
    if (clientWorkGroup != null) {
      clientWorkGroup.shutdownGracefully();
    }
  }
}
