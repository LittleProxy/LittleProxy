package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.littleshoot.proxy.AbstractProxyTest;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.ResponseInfo;

final class ConnectConnectionLifecycleTest extends AbstractProxyTest {
  private final AtomicInteger successes = new AtomicInteger();
  private final AtomicReference<ChannelHandlerContext> serverContext = new AtomicReference<>();

  @Override
  protected void setUp() {
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withConnectTimeout(1000)
            .withFiltersSource(
                new HttpFiltersSourceAdapter() {
                  @Override
                  public HttpFilters filterRequest(HttpRequest request, ChannelHandlerContext ctx) {
                    return new HttpFiltersAdapter(request, ctx) {
                      @Override
                      public void proxyToServerConnectionSucceeded(
                          ChannelHandlerContext serverCtx) {
                        if (HttpMethod.CONNECT.equals(request.method())) {
                          serverContext.set(serverCtx);
                          successes.incrementAndGet();
                        }
                      }
                    };
                  }
                })
            .start();
  }

  @Test
  void httpsTunnelReportsConnectionSuccessExactlyOnce() {
    ResponseInfo response = httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(successes.get()).isEqualTo(1);
    assertThat(serverContext.get()).isNotNull();
    ProxyToServerConnection connection = (ProxyToServerConnection) serverContext.get().handler();
    assertThat(connection.getLogTarget()).isEqualTo("127.0.0.1:" + httpsWebServerPort);
  }

  @Test
  @Tag("slow-test")
  @Timeout(20)
  void realUpstreamResetLogsTargetSideAndConnectionId() throws Exception {
    CountDownLatch logged = new CountDownLatch(1);
    AtomicReference<String> resetMessage = new AtomicReference<>();
    // Capture the configured SLF4J binding; production logging still uses SLF4J only.
    org.apache.logging.log4j.core.Logger logger =
        (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ProxyToServerConnection.class);
    AbstractAppender capture =
        new AbstractAppender("connect-reset-test", null, null, true, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            String message = event.getMessage().getFormattedMessage();
            if (message.contains("An IOException occurred on ProxyToServerConnection")) {
              resetMessage.set(message);
              logged.countDown();
            }
          }
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    capture.start();
    logger.addAppender(capture);
    try (ServerSocket upstream = new ServerSocket()) {
      upstream.bind(new InetSocketAddress("127.0.0.1", 0));
      upstream.setSoTimeout(5000);
      Future<?> reset =
          executor.submit(
              () -> {
                try (Socket socket = upstream.accept()) {
                  socket.setSoTimeout(5000);
                  assertThat(socket.getInputStream().read()).isEqualTo(42);
                  socket.setSoLinger(true, 0);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
      String target = "127.0.0.1:" + upstream.getLocalPort();
      try (Socket client = new Socket("127.0.0.1", proxyServer.getListenAddress().getPort())) {
        client.setSoTimeout(5000);
        client
            .getOutputStream()
            .write(
                ("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        InputStream input = client.getInputStream();
        StringBuilder header = new StringBuilder();
        while (!header.toString().endsWith("\r\n\r\n") && header.length() < 8192) {
          int value = input.read();
          assertThat(value).isNotEqualTo(-1);
          header.append((char) value);
        }
        assertThat(header.toString()).startsWith("HTTP/1.1 200");
        assertThat(successes.get()).isEqualTo(1);
        client.getOutputStream().write(42);
        reset.get(5, TimeUnit.SECONDS);
        assertThat(logged.await(5, TimeUnit.SECONDS)).isTrue();
        ProxyToServerConnection connection =
            (ProxyToServerConnection) serverContext.get().handler();
        assertThat(resetMessage.get())
            .contains("target=" + target, "side=upstream", "id=" + connection.getId());
      }
    } finally {
      logger.removeAppender(capture);
      capture.stop();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
