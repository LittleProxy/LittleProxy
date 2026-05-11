package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.ConcurrentMapServerConnectionPool;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.ServerConnectionPool;

/**
 * Integration tests for the shared ServerConnectionPool feature. Tests that connection pooling
 * works correctly when enabled.
 */
public class SharedConnectionPoolTest extends BaseProxyTest {

  @Override
  protected void setUp() {
    // Enable the shared server connection pool
    proxyServer = bootstrapProxy().withSharedServerConnectionPool(true).withPort(0).start();
  }

  @Test
  void testSimpleGetRequestWithPoolEnabled() {
    // Basic test - verify the proxy works with pool enabled
    ResponseInfo response = httpGetWithApacheClient(webHost, DEFAULT_RESOURCE, true, false);
    assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void testSimplePostRequestWithPoolEnabled() {
    // Test POST request works with pool enabled
    ResponseInfo response = httpPostWithApacheClient(webHost, DEFAULT_RESOURCE, true);
    assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void testMultipleRequestsToSameServerWithPoolEnabled() {
    // Make multiple requests to the same server - with pool enabled,
    // these should reuse connections
    for (int i = 0; i < 5; i++) {
      ResponseInfo response = httpGetWithApacheClient(webHost, DEFAULT_RESOURCE, true, false);
      assertThat(response.getStatusCode()).as("Request %d should succeed", i).isEqualTo(200);
    }
  }

  @Test
  void testProxyWorksWithPoolEnabled() {
    // Verify the proxy server is configured correctly
    assertThat(proxyServer).isNotNull();
    // The pool should be created when enabled
    assertThat(((DefaultHttpProxyServer) proxyServer).getServerConnectionPool()).isNotNull();
  }

  @Test
  void testDefaultPoolTypeIsConcurrentMap() {
    ServerConnectionPool pool = ((DefaultHttpProxyServer) proxyServer).getServerConnectionPool();
    assertThat(pool).isInstanceOf(ConcurrentMapServerConnectionPool.class);
  }

  @Test
  void testKeepAliveWithPoolEnabled() {
    // Test that keep-alive works with the pool enabled
    // This is important because the pool relies on connection reuse
    ResponseInfo response1 = httpGetWithApacheClient(webHost, DEFAULT_RESOURCE, true, false);
    assertThat(response1.getStatusCode()).isEqualTo(200);

    // Make another request on the same connection
    ResponseInfo response2 = httpGetWithApacheClient(webHost, DEFAULT_RESOURCE, true, false);
    assertThat(response2.getStatusCode()).isEqualTo(200);
  }

  @Test
  void testMaxConnectionsPerHostSetting() {
    // Verify the pool has the correct max connections per host setting
    ServerConnectionPool pool = ((DefaultHttpProxyServer) proxyServer).getServerConnectionPool();
    assertThat(pool).isNotNull();
    assertThat(pool.getMaxConnectionsPerHost()).isEqualTo(10);
    assertThat(pool.getMaxConnections()).isEqualTo(200);
  }
}
