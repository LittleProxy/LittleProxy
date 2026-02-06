package org.littleshoot.proxy;

import org.littleshoot.proxy.extras.TestMitmManager;

/** Tests a single proxy that requires username/password authentication and that uses MITM. */
public class MITMUsernamePasswordAuthenticatingProxyTest
    extends UsernamePasswordAuthenticatingProxyTest implements ProxyAuthenticator {
  @Override
  protected void setUp() {
    proxyServer =
        bootstrapProxy()
            .withPort(0)
            .withProxyAuthenticator(this)
            .withManInTheMiddle(new TestMitmManager())
            .start();
  }

  @Override
  protected boolean isMITM() {
    return true;
  }
}
