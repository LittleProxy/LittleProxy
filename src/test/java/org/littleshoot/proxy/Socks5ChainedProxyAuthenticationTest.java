package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.buildHttpClient;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.SocksMessage;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Integration test for SOCKS5 proxy authentication (issue #57). Tests that upstream SOCKS5 proxy
 * authentication works correctly.
 *
 * <p>This test verifies that: 1. LittleProxy correctly sends username/password authentication to an
 * upstream SOCKS5 proxy 2. The authentication flow completes successfully 3. The credentials are
 * transmitted correctly
 */
class Socks5ChainedProxyAuthenticationTest {

  private static final String SOCKS_USERNAME = "testuser";
  private static final String SOCKS_PASSWORD = "testpass";
  private static final String DEFAULT_JKS_KEYSTORE_PATH = "target/littleproxy_keystore.jks";
  private static final Logger LOGGER =
      LogManager.getLogger(Socks5ChainedProxyAuthenticationTest.class);
  private EventLoopGroup socksBossGroup;
  private EventLoopGroup socksWorkerGroup;
  private int socksPort;
  private Channel socksServerChannel;

  private Server webServer;
  private int webServerPort;
  private HttpProxyServer proxyServer;

  // Track authentication attempts for verification
  private final AtomicInteger authAttempts = new AtomicInteger(0);
  private final AtomicInteger authSuccesses = new AtomicInteger(0);
  private final AtomicInteger connectRequests = new AtomicInteger(0);

  // Latch to track when we've received all expected messages
  private final CountDownLatch authCompleteLatch = new CountDownLatch(1);

  @BeforeEach
  void setUp() throws Exception {
    // Start a simple web server
    webServer = TestUtils.startWebServer(true, DEFAULT_JKS_KEYSTORE_PATH);
    webServerPort = TestUtils.findLocalHttpPort(webServer);

    // Start SOCKS5 server with authentication
    initializeSocksServer();

    // Start LittleProxy with chained SOCKS5 proxy
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withName("Downstream")
            .withPort(0)
            .withChainProxyManager(chainedProxyManagerWithAuth())
            .start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (proxyServer != null) {
      proxyServer.abort();
    }
    if (webServer != null) {
      webServer.stop();
    }
    if (socksServerChannel != null) {
      socksServerChannel.close();
    }
    if (socksBossGroup != null) {
      socksBossGroup.shutdownGracefully();
    }
    if (socksWorkerGroup != null) {
      socksWorkerGroup.shutdownGracefully();
    }
  }

  private void initializeSocksServer() throws Exception {
    socksBossGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    socksWorkerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap
        .group(socksBossGroup, socksWorkerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(new Socks5AuthServerInitializer());

    ChannelFuture channelFuture = bootstrap.bind(0).sync();
    socksServerChannel = channelFuture.channel();
    socksPort = ((InetSocketAddress) socksServerChannel.localAddress()).getPort();
    LOGGER.info("SOCKS5 server with auth started on port {}", socksPort);
  }

  private ChainedProxyManager chainedProxyManagerWithAuth() {
    return (httpRequest, chainedProxies, clientDetails) -> {
      chainedProxies.add(
          new ChainedProxyAdapter() {
            @Override
            public InetSocketAddress getChainedProxyAddress() {
              return new InetSocketAddress("127.0.0.1", socksPort);
            }

            @Override
            public ChainedProxyType getChainedProxyType() {
              return ChainedProxyType.SOCKS5;
            }

            @Override
            public String getUsername() {
              return SOCKS_USERNAME;
            }

            @Override
            public String getPassword() {
              return SOCKS_PASSWORD;
            }
          });
    };
  }

  /**
   * Tests that SOCKS5 proxy authentication works correctly.
   *
   * <p>This test verifies the authentication flow between LittleProxy and an upstream SOCKS5 proxy
   * that requires username/password authentication (issue #57).
   *
   * <p>The test demonstrates that: 1. LittleProxy correctly negotiates authentication with the
   * SOCKS5 proxy 2. The username and password are transmitted correctly 3. Authentication succeeds
   */
  @Test
  void testSocks5Authentication() throws Exception {
    // Make a request through the proxy - this will trigger the authentication
    // We expect this to fail because our test server closes the connection after auth
    // but that's fine - we're testing authentication, not the full proxy functionality

    // Use a very short connect timeout so the test doesn't hang
    org.apache.http.client.config.RequestConfig config =
        org.apache.http.client.config.RequestConfig.custom()
            .setConnectTimeout(2000)
            .setSocketTimeout(2000)
            .build();

    try (CloseableHttpClient httpClient =
        buildHttpClient(true, true, proxyServer.getListenAddress().getPort(), null, null)) {
      HttpGet request = new HttpGet("http://127.0.0.1:" + webServerPort + "/");
      request.setConfig(config);
      try {
        HttpResponse response =
            httpClient.execute(new HttpHost("127.0.0.1", webServerPort), request);
        EntityUtils.consumeQuietly(response.getEntity());
      } catch (Exception e) {
        // Expected - our test SOCKS server closes connection after auth
        LOGGER.info("Request failed as expected: {}", e.getMessage());
      }
    }

    // Wait for the authentication to complete
    boolean authCompleted = authCompleteLatch.await(5, TimeUnit.SECONDS);
    assertThat(authCompleted).as("Authentication should complete").isTrue();

    // Verify authentication was attempted
    assertThat(authAttempts.get()).as("Authentication attempts").isGreaterThanOrEqualTo(1);

    // Verify authentication succeeded
    assertThat(authSuccesses.get()).as("Authentication successes").isEqualTo(1);

    // Verify we received a CONNECT request after successful auth
    assertThat(connectRequests.get()).as("CONNECT requests").isGreaterThanOrEqualTo(1);

    LOGGER.info("=== TEST PASSED ===");
    LOGGER.info("SOCKS5 authentication flow works correctly!");
    LOGGER.info("Authentication attempts: {}", authAttempts.get());
    LOGGER.info("Authentication successes: {}", authSuccesses.get());
    LOGGER.info("CONNECT requests: {}", connectRequests.get());
  }

  /** Custom SOCKS5 server initializer that supports username/password authentication. */
  private class Socks5AuthServerInitializer extends ChannelInitializer<Channel> {
    @Override
    protected void initChannel(Channel ch) throws Exception {
      // Use SocksPortUnificationServerHandler to detect SOCKS protocol version automatically
      ch.pipeline().addLast(new io.netty.handler.codec.socksx.SocksPortUnificationServerHandler());
      ch.pipeline().addLast(new Socks5AuthServerHandler());
    }
  }

  /**
   * SOCKS5 server handler that implements username/password authentication. This handler requires
   * authentication and tracks the authentication flow.
   */
  private class Socks5AuthServerHandler extends SimpleChannelInboundHandler<SocksMessage> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, SocksMessage socksRequest)
        throws Exception {
      LOGGER.info("SOCKS: Received {}", socksRequest.getClass().getSimpleName());

      if (socksRequest instanceof io.netty.handler.codec.socksx.v5.Socks5InitialRequest) {
        io.netty.handler.codec.socksx.v5.Socks5InitialRequest request =
            (io.netty.handler.codec.socksx.v5.Socks5InitialRequest) socksRequest;
        LOGGER.info("SOCKS: Client supported auth methods: {}", request.authMethods());

        // Verify client supports both NO_AUTH and PASSWORD
        assertThat(request.authMethods()).contains(Socks5AuthMethod.NO_AUTH);
        assertThat(request.authMethods()).contains(Socks5AuthMethod.PASSWORD);

        // Require password authentication
        ctx.pipeline().addFirst(new Socks5PasswordAuthRequestDecoder());
        ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD));
        LOGGER.info("SOCKS: Sent auth method response - requiring PASSWORD");

      } else if (socksRequest instanceof Socks5PasswordAuthRequest) {
        Socks5PasswordAuthRequest authRequest = (Socks5PasswordAuthRequest) socksRequest;
        String username = authRequest.username();
        String password = authRequest.password();

        LOGGER.info("SOCKS: Received password auth - username:'{}'", username);

        authAttempts.incrementAndGet();

        // Verify credentials are correct
        assertThat(username).as("Username").isEqualTo(SOCKS_USERNAME);
        assertThat(password).as("Password").isEqualTo(SOCKS_PASSWORD);

        // Accept credentials
        ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
        authSuccesses.incrementAndGet();
        LOGGER.info("SOCKS: Authentication SUCCESS!");

      } else if (socksRequest instanceof Socks5CommandRequest) {
        Socks5CommandRequest request = (Socks5CommandRequest) socksRequest;
        LOGGER.info("SOCKS: CONNECT request to '{}:{}'", request.dstAddr(), request.dstPort());

        connectRequests.incrementAndGet();

        // Signal that auth is complete
        authCompleteLatch.countDown();

        // Close connection (test mode)
        ctx.close();
        LOGGER.info("SOCKS: Closed connection (test mode)");
      }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
      ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      LOGGER.info("SOCKS: Exception - {}", cause.getMessage());
      ctx.close();
    }
  }
}
