package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.CommonsPoolServerConnectionPool;
import org.littleshoot.proxy.impl.ConcurrentMapServerConnectionPool;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.ServerConnectionPool;
import org.littleshoot.proxy.impl.StormpotServerConnectionPool;

class ServerConnectionPoolTypeTest {

  @Test
  void shouldCreateConcurrentMapPool() {
    DefaultHttpProxyServer server = startServer(ServerConnectionPoolType.CONCURRENT_MAP, 3, 7);
    try {
      ServerConnectionPool pool = server.getServerConnectionPool();
      assertThat(pool).isInstanceOf(ConcurrentMapServerConnectionPool.class);
      assertThat(pool.getMaxConnectionsPerHost()).isEqualTo(3);
      assertThat(pool.getMaxConnections()).isEqualTo(7);
    } finally {
      server.abort();
    }
  }

  @Test
  void shouldCreateCommonsPool2Pool() {
    DefaultHttpProxyServer server = startServer(ServerConnectionPoolType.COMMONS_POOL2, 4, 9);
    try {
      ServerConnectionPool pool = server.getServerConnectionPool();
      assertThat(pool).isInstanceOf(CommonsPoolServerConnectionPool.class);
      assertThat(pool.getMaxConnectionsPerHost()).isEqualTo(4);
      assertThat(pool.getMaxConnections()).isEqualTo(9);
    } finally {
      server.abort();
    }
  }

  @Test
  void shouldCreateStormpotPool() {
    DefaultHttpProxyServer server = startServer(ServerConnectionPoolType.STORMPOT, 5, 11);
    try {
      ServerConnectionPool pool = server.getServerConnectionPool();
      assertThat(pool).isInstanceOf(StormpotServerConnectionPool.class);
      assertThat(pool.getMaxConnectionsPerHost()).isEqualTo(5);
      assertThat(pool.getMaxConnections()).isEqualTo(11);
    } finally {
      server.abort();
    }
  }

  @Test
  void shouldReturnNullWhenPoolDisabled() {
    DefaultHttpProxyServer server =
        (DefaultHttpProxyServer)
            DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withSharedServerConnectionPool(false)
                .start();
    try {
      assertThat(server.getServerConnectionPool()).isNull();
    } finally {
      server.abort();
    }
  }

  @Test
  void shouldReturnSameInstanceOnRepeatedCalls() {
    DefaultHttpProxyServer server = startServer(ServerConnectionPoolType.CONCURRENT_MAP, 3, 7);
    try {
      ServerConnectionPool first = server.getServerConnectionPool();
      ServerConnectionPool second = server.getServerConnectionPool();
      assertThat(second).isSameAs(first);
    } finally {
      server.abort();
    }
  }

  @Test
  void shouldReturnSameInstanceUnderConcurrentAccess() throws Exception {
    DefaultHttpProxyServer server = startServer(ServerConnectionPoolType.CONCURRENT_MAP, 3, 7);
    try {
      int threadCount = 10;
      CountDownLatch latch = new CountDownLatch(threadCount);
      AtomicReference<ServerConnectionPool>[] refs = new AtomicReference[threadCount];
      for (int i = 0; i < threadCount; i++) {
        refs[i] = new AtomicReference<>();
        int idx = i;
        new Thread(
                () -> {
                  refs[idx].set(server.getServerConnectionPool());
                  latch.countDown();
                })
            .start();
      }
      latch.await();
      ServerConnectionPool first = refs[0].get();
      for (int i = 1; i < threadCount; i++) {
        assertThat(refs[i].get()).isSameAs(first);
      }
    } finally {
      server.abort();
    }
  }

  private static DefaultHttpProxyServer startServer(
      ServerConnectionPoolType poolType, int maxConnectionsPerHost, int maxConnections) {
    return (DefaultHttpProxyServer)
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withSharedServerConnectionPool(true)
            .withServerConnectionPoolType(poolType)
            .withMaxConnectionsPerHost(maxConnectionsPerHost)
            .withMaxConnections(maxConnections)
            .start();
  }
}
