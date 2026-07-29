package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.extras.TestMitmManager;

/**
 * Integration tests for per-request MITM pooling. When poolPerRequestInMitm is enabled, each HTTP
 * request through the MITM tunnel independently acquires and releases a server connection from the
 * shared pool, rather than using a dedicated per-session connection.
 */
@Tag("slow-test")
public class MitmWithPerRequestPoolTest extends AbstractProxyTest {

  @Override
  protected void setUp() {
    proxyServer =
        bootstrapProxy()
            .withPort(0)
            .withManInTheMiddle(new TestMitmManager())
            .withSharedServerConnectionPool(true)
            .withPoolSharedMitmConnections(true)
            .withPoolPerRequestInMitm(true)
            .start();
  }

  @Override
  protected boolean isMITM() {
    return true;
  }

  @Test
  void testGetRequestOverHTTPS() {
    ResponseInfo response = httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);
    assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void testPostRequestOverHTTPS() {
    ResponseInfo response = httpPostWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true);
    assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void testMultipleRequestsOverHTTPS() {
    for (int i = 0; i < 5; i++) {
      ResponseInfo response = httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);
      assertThat(response.getStatusCode()).as("Request %d should succeed", i).isEqualTo(200);
    }
  }
}
