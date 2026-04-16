package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

/** Unit tests for PendingRequest and defaults. */
public class ConcurrentMapServerConnectionPoolTest {

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
}
