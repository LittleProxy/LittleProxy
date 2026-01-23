package org.littleshoot.proxy;

public final class UnencryptedTCPChainedProxyTest extends BaseChainedProxyTest {
  @Override
  protected HttpProxyServerBootstrap upstreamProxy() {
    return super.upstreamProxy();
  }
}
