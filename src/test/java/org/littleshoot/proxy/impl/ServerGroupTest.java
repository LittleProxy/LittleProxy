package org.littleshoot.proxy.impl;

import static org.junit.jupiter.api.Assertions.*;

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
    assertTrue(serverGroup.isStopped(), "serverGroup.isStopped");
    assertThrows(IllegalStateException.class, this::startAndStopProxyServer);
  }

  @Test
  void manualStop() {
    serverGroup = new ServerGroup("Test", 4, 4, 4, false);
    startAndStopProxyServer();
    assertFalse(serverGroup.isStopped(), "serverGroup.isStopped");
    startAndStopProxyServer();
  }

  @AfterEach
  void shutdown() {
    if (serverGroup != null) {
      serverGroup.shutdown(false);
    }
  }
}
