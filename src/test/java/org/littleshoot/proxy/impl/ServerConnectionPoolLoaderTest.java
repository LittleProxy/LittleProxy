package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ServerConnectionPoolLoaderTest {

  private final ServerConnectionPoolContext context =
      ServerConnectionPoolContext.builder()
          .server(mock(DefaultHttpProxyServer.class))
          .options(Collections.emptyMap())
          .build();

  @Test
  void shouldSelectRequestedPoolByName() {
    ServerConnectionPool fake = fakePool("FAKE");
    ServerConnectionPoolLoader loader = new ServerConnectionPoolLoader(Arrays.asList(fake));

    ServerConnectionPool pool = loader.load("FAKE", context);

    assertThat(pool).isSameAs(fake);
    verify(fake).initialize(context);
  }

  @Test
  void shouldFallBackToDefaultRegisteredPoolWhenNameUnknown() {
    ServerConnectionPool defaultPool = fakePool("CONCURRENT_MAP");
    ServerConnectionPoolLoader loader =
        new ServerConnectionPoolLoader(Collections.singletonList(defaultPool));

    ServerConnectionPool pool = loader.load("NO_SUCH_POOL", context);

    assertThat(pool).isSameAs(defaultPool);
  }

  @Test
  void shouldFallBackToHardcodedPoolWhenNothingRegistered() {
    ServerConnectionPoolLoader loader = new ServerConnectionPoolLoader(Collections.emptyList());

    ServerConnectionPool pool = loader.load("NO_SUCH_POOL", context);

    assertThat(pool).isInstanceOf(ConcurrentMapServerConnectionPool.class);
  }

  @Test
  void shouldThrowWhenNamesAreAmbiguous() {
    ServerConnectionPoolLoader loader =
        new ServerConnectionPoolLoader(Arrays.asList(fakePool("DUP"), fakePool("DUP")));

    assertThatThrownBy(() -> loader.load("DUP", context))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Ambiguous");
  }

  @Test
  void shouldThrowWhenNameIsBlank() {
    ServerConnectionPool blank = fakePool(null);
    ServerConnectionPoolLoader loader =
        new ServerConnectionPoolLoader(Collections.singletonList(blank));

    assertThatThrownBy(() -> loader.load("X", context)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldRejectDoubleInitialize() {
    ConcurrentMapServerConnectionPool pool = new ConcurrentMapServerConnectionPool();
    pool.initialize(context);

    assertThatThrownBy(() -> pool.initialize(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already initialized");
  }

  @Test
  void shouldReadStandardOptionsFromContext() {
    DefaultHttpProxyServer server = mock(DefaultHttpProxyServer.class);
    ServerConnectionPoolContext ctx =
        ServerConnectionPoolContext.builder()
            .server(server)
            .option(ServerConnectionPoolContext.OPTION_MAX_CONNECTIONS_PER_HOST, 3)
            .option(ServerConnectionPoolContext.OPTION_MAX_CONNECTIONS, 7)
            .option(ServerConnectionPoolContext.OPTION_IDLE_TIMEOUT, Duration.ofSeconds(90))
            .build();

    ConcurrentMapServerConnectionPool pool = new ConcurrentMapServerConnectionPool();
    pool.initialize(ctx);

    assertThat(pool.getMaxConnectionsPerHost()).isEqualTo(3);
    assertThat(pool.getMaxConnections()).isEqualTo(7);
    assertThat(pool.getIdleTimeout()).isEqualTo(Duration.ofSeconds(90));
  }

  private static ServerConnectionPool fakePool(String name) {
    ServerConnectionPool pool = mock(ServerConnectionPool.class);
    when(pool.getName()).thenReturn(name);
    return pool;
  }
}
