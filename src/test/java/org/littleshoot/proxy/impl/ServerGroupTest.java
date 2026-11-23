package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HttpProxyServer;

class ServerGroupTest {
  private ServerGroup serverGroup;

  private void startAndStopProxyServer() {
    HttpProxyServer proxyServer =
        DefaultHttpProxyServer.bootstrap().withPort(0).withServerGroup(serverGroup).start();
    proxyServer.stop();
  }

  @Test
  void autoStop() {
    serverGroup = new ServerGroup("Test", 4, 4, 4);
    startAndStopProxyServer();
    assertThat(serverGroup.isStopped()).as("serverGroup.isStopped").isTrue();
    assertThatThrownBy(this::startAndStopProxyServer).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void manualStop() {
    serverGroup = new ServerGroup("Test", 4, 4, 4, false);
    startAndStopProxyServer();
    assertThat(serverGroup.isStopped()).as("serverGroup.isStopped").isFalse();
    startAndStopProxyServer();
  }

  @AfterEach
  void shutdown() {
    if (serverGroup != null) {
      serverGroup.shutdown(false);
    }
  }
}
