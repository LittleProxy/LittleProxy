package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.extras.TestMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.PoolMetrics;
import org.littleshoot.proxy.impl.ServerConnectionPool;

/**
 * Integration tests for MITM with shared server connection pool. Tests that upstream TLS
 * connections are pooled and reused across MITM sessions when poolSharedMitmConnections is enabled.
 */
public class MitmWithSharedPoolTest extends AbstractProxyTest {

  @Override
  protected void setUp() {
    proxyServer =
        bootstrapProxy()
            .withPort(0)
            .withManInTheMiddle(new TestMitmManager())
            .withSharedServerConnectionPool(true)
            .withPoolSharedMitmConnections(true)
            .start();
  }

  @Override
  protected boolean isMITM() {
    return true;
  }

  @Test
  void testMitmGetRequestOverHTTPS() {
    ResponseInfo response = httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);
    assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void testMitmPostRequestOverHTTPS() {
    ResponseInfo response = httpPostWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true);
    assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void testMitmGetRequestOverHTTPSFromDifferentClients() {
    for (int i = 0; i < 3; i++) {
      ResponseInfo response = httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);
      assertThat(response.getStatusCode())
          .as("Request from client %d should succeed", i)
          .isEqualTo(200);
    }
  }

  @Test
  void testSharedPoolMetricsShowConnectionReuse() {
    DefaultHttpProxyServer impl = (DefaultHttpProxyServer) proxyServer;
    ServerConnectionPool pool = impl.getServerConnectionPool();

    httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);
    PoolMetrics afterFirst = pool.getMetrics();
    assertThat(afterFirst.getTotalConnections()).isGreaterThanOrEqualTo(1);
    assertThat(afterFirst.getBorrowCount()).isGreaterThanOrEqualTo(1);

    httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);
    PoolMetrics afterSecond = pool.getMetrics();
    assertThat(afterSecond.getBorrowCount()).isGreaterThan(afterFirst.getBorrowCount());
  }
}
