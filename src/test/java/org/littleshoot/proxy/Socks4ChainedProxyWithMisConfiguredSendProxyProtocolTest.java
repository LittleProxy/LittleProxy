package org.littleshoot.proxy;

public final class Socks4ChainedProxyWithMisConfiguredSendProxyProtocolTest
    extends BaseChainedSocksProxyTest {
  @Override
  protected ChainedProxyType getSocksProxyType() {
    return ChainedProxyType.SOCKS4;
  }

  @Override
  protected void setUp() throws Exception {
    initializeSocksServer();
    proxyServer =
        bootstrapProxy()
            .withName("Downstream")
            .withPort(0)
            .withChainProxyManager(chainedProxyManager())
            // misconfigured option
            .withSendProxyProtocol(true)
            .start();
  }
}
