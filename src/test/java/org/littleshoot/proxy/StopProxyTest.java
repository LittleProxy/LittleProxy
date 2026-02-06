package org.littleshoot.proxy;

import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

public final class StopProxyTest {
  @Test
  public void testStop() {
    HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap().withPort(0).start();

    proxyServer.stop();
  }

  @Test
  public void testAbort() {
    HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap().withPort(0).start();

    proxyServer.abort();
  }
}
