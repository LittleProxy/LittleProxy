package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

/** Unit tests for ProxyToServerConnectionPool.PendingRequest. */
public class ProxyToServerConnectionPoolTest {

  @Test
  void pendingRequestShouldStoreDataCorrectly() {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

    ProxyToServerConnectionPool.PendingRequest pendingRequest =
        new ProxyToServerConnectionPool.PendingRequest(null, request);

    assertThat(pendingRequest.getClientConnection()).isNull();
    assertThat(pendingRequest.getRequest()).isSameAs(request);
    assertThat(pendingRequest.getTimestamp()).isGreaterThan(0);
  }

  @Test
  void pendingRequestTimestampShouldBeRecent() {
    long before = System.currentTimeMillis();
    ProxyToServerConnectionPool.PendingRequest pendingRequest =
        new ProxyToServerConnectionPool.PendingRequest(null, null);
    long after = System.currentTimeMillis();

    assertThat(pendingRequest.getTimestamp()).isGreaterThanOrEqualTo(before);
    assertThat(pendingRequest.getTimestamp()).isLessThanOrEqualTo(after);
  }

  @Test
  void poolShouldHaveDefaultMaxConnectionsPerHost() throws Exception {
    var field =
        ProxyToServerConnectionPool.class.getDeclaredField("DEFAULT_MAX_CONNECTIONS_PER_HOST");
    field.setAccessible(true);
    assertThat(field.get(null)).isEqualTo(10);
  }

  @Test
  void poolShouldHaveDefaultMaxTotalConnections() throws Exception {
    var field = ProxyToServerConnectionPool.class.getDeclaredField("DEFAULT_MAX_TOTAL_CONNECTIONS");
    field.setAccessible(true);
    assertThat(field.get(null)).isEqualTo(200);
  }
}
