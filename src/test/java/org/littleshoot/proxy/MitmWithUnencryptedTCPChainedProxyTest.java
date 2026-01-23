package org.littleshoot.proxy;

public final class MitmWithUnencryptedTCPChainedProxyTest extends MitmWithChainedProxyTest {
  @Override
  protected HttpProxyServerBootstrap upstreamProxy() {
    return super.upstreamProxy();
  }
}
