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
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;

class ConcurrentMapServerConnectionPoolTest {

  private DefaultHttpProxyServer mockProxyServer;
  private GlobalTrafficShapingHandler mockTrafficHandler;
  private ClientToProxyConnection mockClientConnection;
  private HostResolver mockHostResolver;
  private ConcurrentMapServerConnectionPool pool;

  @BeforeEach
  void setUp() throws Exception {
    mockProxyServer = mock();
    mockTrafficHandler = mock();
    mockClientConnection = mock();
    mockHostResolver = mock();

    when(mockProxyServer.getChainProxyManager()).thenReturn(null);
    when(mockProxyServer.getServerResolver()).thenReturn(mockHostResolver);
    when(mockHostResolver.resolve(anyString(), anyInt()))
        .thenReturn(new InetSocketAddress("127.0.0.1", 8080));
    when(mockProxyServer.getActivityTrackers()).thenReturn(java.util.Collections.emptyList());

    when(mockClientConnection.flowContext()).thenReturn(mock());
    when(mockClientConnection.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mock());

    pool = new ConcurrentMapServerConnectionPool(mockProxyServer, mockTrafficHandler);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /** Creates a mock ProxyToServerConnection with the given availability. */
  private ProxyToServerConnection createMockConnection(
      boolean connected, boolean availableForNewRequest) throws Exception {
    ProxyToServerConnection conn = mock();
    when(conn.isConnected()).thenReturn(connected);
    when(conn.isAvailableForNewRequest()).thenReturn(availableForNewRequest);
    when(conn.getServerHostAndPort()).thenReturn("example.com:80");
    return conn;
  }

  /**
   * Registers a connection in the pool so it appears as a tracked, connected connection. After
   * this, {@link #releaseConnection} can be called to add it to the available queue.
   */
  @SuppressWarnings("unchecked")
  private void registerInPool(ProxyToServerConnection conn, String poolKey) throws Exception {

    ConcurrentMap<ProxyToServerConnection, String> keys = getField(pool, "connectionKeys");
    keys.put(conn, poolKey);

    ConcurrentMap<String, ConcurrentMap<ProxyToServerConnection, Boolean>> connectionsByHost =
        getField(pool, "connectionsByHostAndPort");
    connectionsByHost
        .computeIfAbsent(poolKey, k -> new ConcurrentHashMap<>())
        .put(conn, Boolean.TRUE);

    ConcurrentMap<String, java.util.concurrent.atomic.AtomicInteger> counts =
        getField(pool, "connectionCountByHostAndPort");
    counts
        .computeIfAbsent(poolKey, k -> new java.util.concurrent.atomic.AtomicInteger(0))
        .incrementAndGet();
  }

  private static Field field(Class<?> clazz, String name) throws Exception {
    Class<?> current = clazz;
    while (current != null) {
      try {
        Field f = current.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name + " in " + clazz.getName());
  }

  @SuppressWarnings("unchecked")
  private static <T> T getField(Object obj, String name) throws Exception {
    Field f = field(obj.getClass(), name);
    return (T) f.get(obj);
  }

  // -----------------------------------------------------------------------
  // PendingRequest tests (from original test, no reflection)
  // -----------------------------------------------------------------------

  @Test
  void pendingRequestShouldStoreDataCorrectly() {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    PendingRequest pendingRequest = new PendingRequest(null, request, null);
    assertThat(pendingRequest.getClientConnection()).isNull();
    assertThat(pendingRequest.getRequest()).isSameAs(request);
    assertThat(pendingRequest.getFilters()).isNull();
    assertThat(pendingRequest.getTimestamp()).isGreaterThan(0);
  }

  @Test
  @Tag("slow-test")
  void pendingRequestTimestampShouldBeRecent() {
    long before = System.currentTimeMillis();
    PendingRequest pendingRequest = new PendingRequest(null, null, null);
    long after = System.currentTimeMillis();
    assertThat(pendingRequest.getTimestamp()).isGreaterThanOrEqualTo(before);
    assertThat(pendingRequest.getTimestamp()).isLessThanOrEqualTo(after);
  }

  @Test
  void poolShouldHaveDefaultMaxConnectionsPerHost() {
    assertThat(ConcurrentMapServerConnectionPool.DEFAULT_MAX_CONNECTIONS_PER_HOST).isEqualTo(10);
  }

  @Test
  void poolShouldHaveDefaultMaxTotalConnections() {
    assertThat(ConcurrentMapServerConnectionPool.DEFAULT_MAX_TOTAL_CONNECTIONS).isEqualTo(200);
  }

  // -----------------------------------------------------------------------
  // releaseConnection (public API)
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("releaseConnection should add connected connection to available queue")
  void releaseConnectionShouldAddToAvailableQueue() throws Exception {
    ProxyToServerConnection conn = createMockConnection(true, true);
    registerInPool(conn, "example.com:80:direct");

    pool.releaseConnection(conn);

    Queue<?> queue =
        ((java.util.Map<String, Queue<?>>) getField(pool, "availableConnectionsByHostAndPort"))
            .get("example.com:80:direct");
    assertThat(queue).isNotNull().hasSize(1);
  }

  @Test
  @DisplayName("releaseConnection should call removeConnection for disconnected connections")
  void releaseConnectionShouldRemoveDisconnectedConnections() throws Exception {
    ProxyToServerConnection conn = createMockConnection(false, false);
    registerInPool(conn, "example.com:80:direct");

    pool.releaseConnection(conn);

    ConcurrentMap<ProxyToServerConnection, String> keys = getField(pool, "connectionKeys");
    assertThat(keys).doesNotContainKey(conn);
  }

  @Test
  @DisplayName("releaseConnection is no-op if connection is not in connectionKeys")
  void releaseConnectionShouldBeNoOpIfNotTracked() {
    ProxyToServerConnection conn = mock();
    pool.releaseConnection(conn);
    // No exception expected
  }

  @Test
  @DisplayName("releaseConnection is no-op for null")
  void releaseConnectionShouldBeNoOpForNull() {
    pool.releaseConnection(null);
    // No exception expected
  }

  // -----------------------------------------------------------------------
  // removeConnection (public API)
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("removeConnection should clean connection from all maps")
  void removeConnectionShouldCleanAllMaps() throws Exception {
    ProxyToServerConnection conn = createMockConnection(true, true);
    registerInPool(conn, "example.com:80:direct");
    pool.releaseConnection(conn);

    pool.removeConnection(conn);

    ConcurrentMap<ProxyToServerConnection, String> keys = getField(pool, "connectionKeys");
    assertThat(keys).doesNotContainKey(conn);
  }

  @Test
  @DisplayName("removeConnection is no-op for null")
  void removeConnectionShouldBeNoOpForNull() {
    pool.removeConnection(null);
  }

  @Test
  @DisplayName("removeConnection is no-op for untracked connection")
  void removeConnectionShouldBeNoOpForUntrackedConnection() {
    pool.removeConnection(mock(ProxyToServerConnection.class));
  }

  // -----------------------------------------------------------------------
  // borrowAvailableConnection — tested via getOrCreateConnection (public API)
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("getOrCreateConnection should return available connection when one exists")
  void getOrCreateConnectionShouldReturnAvailableConnection() throws Exception {
    ProxyToServerConnection conn = createMockConnection(true, true);
    registerInPool(conn, "example.com:80:direct");
    pool.releaseConnection(conn);

    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    HttpFilters filters =
        new HttpFiltersAdapter(request) {
          @Override
          public InetSocketAddress proxyToServerResolutionStarted(
              String resolvingServerHostAndPort) {
            return null;
          }
        };
    ProxyToServerConnection result =
        pool.getOrCreateConnection("example.com:80", null, mockClientConnection, filters, request);

    assertThat(result).isSameAs(conn);
  }

  @Test
  @DisplayName(
      "borrowAvailableConnection should re-queue busy-but-connected connections "
          + "and return an available one")
  void borrowAvailableConnectionShouldRequeueBusyAndReturnAvailable() throws Exception {
    ProxyToServerConnection busyConn = createMockConnection(true, false);
    ProxyToServerConnection availableConn = createMockConnection(true, true);
    registerInPool(busyConn, "example.com:80:direct");
    registerInPool(availableConn, "example.com:80:direct");
    pool.releaseConnection(busyConn);
    pool.releaseConnection(availableConn);

    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    HttpFilters filters =
        new HttpFiltersAdapter(request) {
          @Override
          public InetSocketAddress proxyToServerResolutionStarted(
              String resolvingServerHostAndPort) {
            return null;
          }
        };
    ProxyToServerConnection result =
        pool.getOrCreateConnection("example.com:80", null, mockClientConnection, filters, request);

    assertThat(result).isSameAs(availableConn);

    // The busy connection should still be in the available queue (re-queued, not lost)
    Queue<?> queue =
        ((java.util.Map<String, Queue<?>>) getField(pool, "availableConnectionsByHostAndPort"))
            .get("example.com:80:direct");
    assertThat(queue).hasSize(1);
  }

  @Test
  @DisplayName("borrowAvailableConnection should not loop infinitely when all connections are busy")
  void borrowAvailableConnectionShouldNotLoopWhenAllBusy() throws Exception {
    ProxyToServerConnection busyConn = createMockConnection(true, false);
    registerInPool(busyConn, "example.com:80:direct");
    pool.releaseConnection(busyConn);

    // getOrCreateConnection -> borrowAvailableConnection tries all connections in
    // the queue, finds none available, returns null. Then getOrCreateConnection
    // creates a new connection.
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    HttpFilters filters =
        new HttpFiltersAdapter(request) {
          @Override
          public InetSocketAddress proxyToServerResolutionStarted(
              String resolvingServerHostAndPort) {
            return null;
          }
        };
    ProxyToServerConnection result =
        pool.getOrCreateConnection("example.com:80", null, mockClientConnection, filters, request);

    // A new connection was created (different from the busy one)
    assertThat(result).isNotNull();
    assertThat(result).isNotSameAs(busyConn);

    // The busy connection should still be in the available queue
    Queue<?> queue =
        ((java.util.Map<String, Queue<?>>) getField(pool, "availableConnectionsByHostAndPort"))
            .get("example.com:80:direct");
    assertThat(queue).hasSize(1);
  }

  // -----------------------------------------------------------------------
  // closeAll (public API)
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("closeAll should shut down the eviction scheduler")
  void closeAllShouldShutdownEvictionScheduler() throws Exception {
    ScheduledExecutorService scheduler = getField(pool, "evictionScheduler");
    assertThat(scheduler.isShutdown()).isFalse();

    pool.closeAll();

    assertThat(scheduler.isShutdown()).isTrue();
  }

  @Test
  @DisplayName("closeAll should clear all internal maps")
  void closeAllShouldClearAllMaps() throws Exception {
    ProxyToServerConnection conn = createMockConnection(true, true);
    registerInPool(conn, "example.com:80:direct");
    pool.releaseConnection(conn);

    pool.closeAll();

    assertThat((java.util.Map<?, ?>) getField(pool, "connectionsByHostAndPort")).isEmpty();
    assertThat((java.util.Map<?, ?>) getField(pool, "availableConnectionsByHostAndPort")).isEmpty();
    assertThat((java.util.Map<?, ?>) getField(pool, "connectionCountByHostAndPort")).isEmpty();
    assertThat((java.util.Map<?, ?>) getField(pool, "connectionKeys")).isEmpty();
  }

  @Test
  @DisplayName("closeAll should be idempotent")
  void closeAllShouldBeIdempotent() {
    pool.closeAll();
    pool.closeAll();
    // No exception on second call
  }

  // -----------------------------------------------------------------------
  // connectionKeys lifecycle (via public API getOrCreateConnection)
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("getOrCreateConnection should store pool key in connectionKeys on creation")
  void getOrCreateConnectionShouldStorePoolKey() {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    HttpFilters filters =
        new HttpFiltersAdapter(request) {
          @Override
          public InetSocketAddress proxyToServerResolutionStarted(
              String resolvingServerHostAndPort) {
            return null;
          }
        };
    ProxyToServerConnection conn =
        pool.getOrCreateConnection("example.com:80", null, mockClientConnection, filters, request);

    assertThat(conn).isNotNull();

    // The connection was created via createForPool, so connectionKeys was set inside
    // getOrCreateConnection. Verify it is there.
    ProxyToServerConnection finalConn = conn; // effectively final
    Runnable check =
        () -> {
          try {
            @SuppressWarnings("unchecked")
            ConcurrentMap<ProxyToServerConnection, String> keys = getField(pool, "connectionKeys");
            assertThat(keys).containsKey(finalConn);
            assertThat(keys.get(finalConn)).isEqualTo("example.com:80:direct");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    check.run();
  }

  @Test
  @DisplayName("getOrCreateConnection should create a new connection when none available")
  void getOrCreateConnectionShouldCreateNewConnection() {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    HttpFilters filters =
        new HttpFiltersAdapter(request) {
          @Override
          public InetSocketAddress proxyToServerResolutionStarted(
              String resolvingServerHostAndPort) {
            return null;
          }
        };
    ProxyToServerConnection conn =
        pool.getOrCreateConnection("example.com:80", null, mockClientConnection, filters, request);
    assertThat(conn).isNotNull();
  }

  // -----------------------------------------------------------------------
  // computePoolKey
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("computePoolKey should use :direct suffix for null chained proxy address")
  void computePoolKeyForDirect() {
    assertThat(pool.computePoolKey("example.com:80", null)).isEqualTo("example.com:80:direct");
  }

  @Test
  @DisplayName("computePoolKey should include resolved chained proxy address")
  void computePoolKeyForChainedProxy() {
    InetSocketAddress proxyAddr = new InetSocketAddress("10.0.0.1", 3128);
    assertThat(pool.computePoolKey("example.com:80", proxyAddr))
        .isEqualTo("example.com:80:10.0.0.1:3128");
  }

  @Test
  @DisplayName("computePoolKey should use hostname for unresolved address")
  void computePoolKeyForUnresolvedChainedProxy() {
    InetSocketAddress proxyAddr = InetSocketAddress.createUnresolved("proxy.example.com", 3128);
    assertThat(pool.computePoolKey("example.com:80", proxyAddr))
        .isEqualTo("example.com:80:proxy.example.com:3128");
  }

  // -----------------------------------------------------------------------
  // PendingRequest queue (public API)
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("drainPendingRequests should drain and remove pending requests")
  void drainPendingRequestsShouldDrainAndRemove() {
    EmbeddedChannel channel = new EmbeddedChannel();
    pool.registerPendingRequest(
        channel,
        mockClientConnection,
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        null);
    pool.drainPendingRequests(channel);

    assertThat(pool.peekPendingRequest(channel)).isNull();
  }

  @Test
  @DisplayName("removePendingRequest should return oldest pending request")
  void removePendingRequestShouldReturnOldest() {
    EmbeddedChannel channel = new EmbeddedChannel();
    pool.registerPendingRequest(
        channel,
        mockClientConnection,
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/first"),
        null);
    pool.registerPendingRequest(
        channel,
        mockClientConnection,
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/second"),
        null);

    PendingRequest first = pool.removePendingRequest(channel);
    assertThat(first).isNotNull();
    assertThat(first.getRequest().uri()).isEqualTo("/first");
  }
}
