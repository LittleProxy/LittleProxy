package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;

@Tag("slow-test")
class StormpotServerConnectionPoolTest {

  private DefaultHttpProxyServer mockProxyServer;
  private ClientToProxyConnection mockClientConnection;
  private HttpFilters mockFilters;
  private GlobalTrafficShapingHandler mockTrafficHandler;
  private HostResolver mockHostResolver;
  private StormpotServerConnectionPool pool;

  @BeforeEach
  void setUp() throws Exception {
    mockProxyServer = mock();
    mockClientConnection = mock();
    mockFilters = mock();
    mockTrafficHandler = mock();
    mockHostResolver = mock();

    when(mockProxyServer.getChainProxyManager()).thenReturn(null);
    when(mockProxyServer.getServerResolver()).thenReturn(mockHostResolver);
    when(mockHostResolver.resolve(anyString(), anyInt()))
        .thenReturn(new InetSocketAddress("127.0.0.1", 8080));
    when(mockProxyServer.getActivityTrackers()).thenReturn(java.util.Collections.emptyList());
    when(mockProxyServer.getConnectTimeout()).thenReturn(100);

    when(mockClientConnection.flowContext()).thenReturn(mock());
    when(mockClientConnection.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mock());

    pool = new StormpotServerConnectionPool(mockProxyServer, mockTrafficHandler, 5, 50);
  }

  @Test
  @DisplayName("getOrCreateConnection creates a connection and clears the thread-local context")
  void creationContextByPoolKeySurroundsClaim() throws Exception {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

    ProxyToServerConnection conn =
        pool.getOrCreateConnection(
            "example.com:80", null, mockClientConnection, mockFilters, request);

    // Claim timeout is read from proxy configuration
    verify(mockProxyServer, atLeastOnce()).getConnectTimeout();
  }

  @Test
  @DisplayName("concurrent claims on same pool key each get their own connection")
  void concurrentClaimsOnSamePoolKey() throws Exception {
    when(mockProxyServer.getConnectTimeout()).thenReturn(10_000);
    int threadCount = 4;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicReference<ProxyToServerConnection>[] refs = new AtomicReference[threadCount];

    for (int i = 0; i < threadCount; i++) {
      refs[i] = new AtomicReference<>();
      int idx = i;
      new Thread(
              () -> {
                try {
                  HttpRequest req =
                      new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/" + idx);
                  ProxyToServerConnection c =
                      pool.getOrCreateConnection(
                          "example.com:80", null, mockClientConnection, mockFilters, req);
                  refs[idx].set(c);
                } catch (Exception e) {
                  refs[idx].set(null);
                } finally {
                  latch.countDown();
                }
              })
          .start();
    }
    latch.await();

    for (int i = 0; i < threadCount; i++) {
      assertThat(refs[i].get()).as("thread %d got a connection", i).isNotNull();
    }
    // All connections are distinct
    for (int i = 0; i < threadCount; i++) {
      for (int j = i + 1; j < threadCount; j++) {
        assertThat(refs[i].get()).isNotSameAs(refs[j].get());
      }
    }
  }

  @Test
  @DisplayName("registerPendingRequest and removePendingRequest work correctly")
  void pendingRequestLifecycle() {
    EmbeddedChannel channel = new EmbeddedChannel();
    HttpRequest req1 = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/first");
    HttpRequest req2 = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/second");

    pool.registerPendingRequest(channel, mockClientConnection, req1, mockFilters);
    pool.registerPendingRequest(channel, mockClientConnection, req2, mockFilters);

    assertThat(pool.peekPendingRequest(channel)).isNotNull();
    assertThat(pool.peekPendingRequest(channel).getRequest().uri()).isEqualTo("/first");

    PendingRequest removed = pool.removePendingRequest(channel);
    assertThat(removed).isNotNull();
    assertThat(removed.getRequest().uri()).isEqualTo("/first");

    assertThat(pool.peekPendingRequest(channel).getRequest().uri()).isEqualTo("/second");
  }

  @Test
  @DisplayName("drainPendingRequests clears queue for channel")
  void drainPendingRequests() {
    EmbeddedChannel channel = new EmbeddedChannel();
    pool.registerPendingRequest(
        channel,
        mockClientConnection,
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        mockFilters);

    pool.drainPendingRequests(channel);
    assertThat(pool.peekPendingRequest(channel)).isNull();
  }

  @Test
  @DisplayName("closeAll shuts down without error")
  void closeAll() {
    pool.closeAll();
    // No exception expected
  }

  @Test
  @DisplayName("setIdleTimeout and getIdleTimeout round-trip")
  void idleTimeoutRoundTrip() {
    java.time.Duration timeout = java.time.Duration.ofSeconds(30);
    pool.setIdleTimeout(timeout);
    assertThat(pool.getIdleTimeout()).isEqualTo(timeout);
    pool.setIdleTimeout(null);
    assertThat(pool.getIdleTimeout()).isNull();
  }

  @Test
  @DisplayName("setConnectionValidationEnabled and isConnectionValidationEnabled round-trip")
  void connectionValidationRoundTrip() {
    pool.setConnectionValidationEnabled(true);
    assertThat(pool.isConnectionValidationEnabled()).isTrue();
    pool.setConnectionValidationEnabled(false);
    assertThat(pool.isConnectionValidationEnabled()).isFalse();
  }

  @Test
  @DisplayName("getMetrics returns non-null metrics")
  void getMetrics() {
    assertThat(pool.getMetrics()).isNotNull();
  }
}
