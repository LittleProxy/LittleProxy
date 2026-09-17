package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HttpProxyServer;

class ServerConnectionPoolContextTest {

  @Test
  void shouldRejectMissingServer() {
    assertThatThrownBy(() -> ServerConnectionPoolContext.builder().option("k", "v").build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("server");
  }

  @Test
  void shouldExposeServerAndImmutableOptions() {
    HttpProxyServer server = mock(HttpProxyServer.class);
    ServerConnectionPoolContext context =
        ServerConnectionPoolContext.builder().server(server).option("k", 1).build();

    assertThat(context.getServer()).isSameAs(server);
    assertThat(context.getOptions()).containsEntry("k", 1).isUnmodifiable();
  }
}
