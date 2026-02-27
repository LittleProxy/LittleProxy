package org.littleshoot.proxy.haproxy;

import static java.lang.String.valueOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.haproxy.HAProxyMessage;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ActivityTrackerAdapter;
import org.littleshoot.proxy.ChainedProxyAdapter;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.HttpProxyServerBootstrap;

public final class ProxyProtocolTest extends BaseProxyProtocolTest {

  private static final String LOCALHOST = "127.0.0.1";
  private static final boolean ACCEPT_PROXY = true;
  private static final boolean SEND_PROXY = true;
  private static final boolean DO_NOT_ACCEPT_PROXY = false;
  private static final boolean DO_NOT_SEND_PROXY = false;

  private final AtomicInteger clientConnectedCount = new AtomicInteger();
  private final AtomicReference<InetSocketAddress> trackerClientAddress = new AtomicReference<>();
  private final AtomicReference<InetSocketAddress> chainedProxyClientAddress =
      new AtomicReference<>();
  private final CountDownLatch clientDisconnectedLatch = new CountDownLatch(1);

  /** Set by a test before {@code setup(...)} to also capture the chained-routing address. */
  private volatile boolean captureChainedProxyClientDetails;

  @Override
  protected void customizeProxyServer(HttpProxyServerBootstrap builder) {
    // Observational tracker; harmless for the header-relay tests that ignore these captures.
    builder.plusActivityTracker(
        new ActivityTrackerAdapter() {
          @Override
          public void clientConnected(FlowContext flowContext) {
            clientConnectedCount.incrementAndGet();
            trackerClientAddress.set(flowContext.getClientAddress());
          }

          @Override
          public void clientDisconnected(FlowContext flowContext, SSLSession sslSession) {
            clientDisconnectedLatch.countDown();
          }
        });

    if (captureChainedProxyClientDetails) {
      // Capture the routing address, then fall back to direct so the flow completes.
      builder.withChainProxyManager(
          (httpRequest, chainedProxies, clientDetails) -> {
            chainedProxyClientAddress.set(clientDetails.getClientAddress());
            chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
          });
    }
  }

  @Test
  public void canRelayProxyProtocolHeader() throws Exception {
    setup(ACCEPT_PROXY, SEND_PROXY);
    HAProxyMessage haProxyMessage = getRelayedHaProxyMessage();
    assertThat(haProxyMessage).isNotNull();
    assertThat(haProxyMessage.sourceAddress()).isEqualTo(SOURCE_ADDRESS);
    assertThat(haProxyMessage.destinationAddress()).isEqualTo(DESTINATION_ADDRESS);
    assertThat(valueOf(haProxyMessage.sourcePort())).isEqualTo(SOURCE_PORT);
    assertThat(valueOf(haProxyMessage.destinationPort())).isEqualTo(DESTINATION_PORT);
  }

  @Test
  public void canSendProxyProtocolHeader() throws Exception {
    setup(DO_NOT_ACCEPT_PROXY, SEND_PROXY);
    HAProxyMessage haProxyMessage = getRelayedHaProxyMessage();
    assertThat(haProxyMessage).isNotNull();
    assertThat(haProxyMessage.sourceAddress()).isEqualTo(LOCALHOST);
    assertThat(haProxyMessage.destinationAddress()).isEqualTo(LOCALHOST);
    assertThat(valueOf(haProxyMessage.destinationPort())).isEqualTo(valueOf(serverPort));
  }

  @Test
  public void canAcceptProxyProtocolHeader() throws Exception {
    setup(ACCEPT_PROXY, DO_NOT_SEND_PROXY);
    HAProxyMessage haProxyMessage = getRelayedHaProxyMessage();
    assertThat(haProxyMessage).isNull();
  }

  /** The PROXY header source address is surfaced to both the tracker and chained routing. */
  @Test
  public void surfacesRealClientAddressFromProxyProtocolHeader() throws Exception {
    captureChainedProxyClientDetails = true;
    setup(ACCEPT_PROXY, SEND_PROXY);

    assertThat(clientDisconnectedLatch.await(5, TimeUnit.SECONDS))
        .as("client should connect and then disconnect within the timeout")
        .isTrue();

    assertThat(trackerClientAddress.get())
        .as("ActivityTracker should see the PROXY header source address")
        .isNotNull();
    assertThat(trackerClientAddress.get().getHostString()).isEqualTo(SOURCE_ADDRESS);
    assertThat(trackerClientAddress.get().getPort()).isEqualTo(Integer.parseInt(SOURCE_PORT));

    assertThat(chainedProxyClientAddress.get())
        .as("ChainedProxyManager (ClientDetails) should see the PROXY header source address")
        .isNotNull();
    assertThat(chainedProxyClientAddress.get().getHostString()).isEqualTo(SOURCE_ADDRESS);
    assertThat(chainedProxyClientAddress.get().getPort()).isEqualTo(Integer.parseInt(SOURCE_PORT));
  }

  /**
   * With no PROXY header, {@code clientConnected} still fires once, from the first request, with
   * the TCP peer address.
   */
  @Test
  public void clientConnectedFiresOnceWithTcpPeerWhenNoProxyHeader() throws Exception {
    setup(DO_NOT_ACCEPT_PROXY, DO_NOT_SEND_PROXY);

    assertThat(clientDisconnectedLatch.await(5, TimeUnit.SECONDS))
        .as("client should connect and then disconnect within the timeout")
        .isTrue();

    assertThat(clientConnectedCount.get())
        .as("clientConnected must fire exactly once across the connection lifecycle")
        .isEqualTo(1);

    assertThat(trackerClientAddress.get())
        .as("clientConnected should carry the TCP peer address when no PROXY header is present")
        .isNotNull();
    assertThat(trackerClientAddress.get().getAddress().isLoopbackAddress())
        .as("client connected over loopback, so the reported address should be a loopback address")
        .isTrue();
  }
}
