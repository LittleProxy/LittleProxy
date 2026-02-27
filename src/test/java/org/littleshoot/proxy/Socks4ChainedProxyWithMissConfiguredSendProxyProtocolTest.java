package org.littleshoot.proxy;

import org.junit.jupiter.api.Tag;

@Tag("slow-test")
public final class Socks4ChainedProxyWithMissConfiguredSendProxyProtocolTest
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
