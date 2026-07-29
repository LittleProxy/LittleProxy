package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;
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
  @DisplayName("creationContextByPoolKey stores and removes context around claim")
  void creationContextByPoolKeySurroundsClaim() throws Exception {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

    ProxyToServerConnection conn =
        pool.getOrCreateConnection(
            "example.com:80", null, mockClientConnection, mockFilters, request);

    // After getOrCreateConnection returns, the context should have been removed
    ConcurrentMap<String, ?> ctxMap = getCtxMap();
    assertThat(ctxMap).isEmpty();
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

  @SuppressWarnings("unchecked")
  private ConcurrentMap<String, ?> getCtxMap() throws Exception {
    Field f = StormpotServerConnectionPool.class.getDeclaredField("creationContextByPoolKey");
    f.setAccessible(true);
    return (ConcurrentMap<String, ?>) f.get(pool);
  }
}
