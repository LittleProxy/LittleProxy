package org.littleshoot.proxy;

public final class Socks4ChainedProxyTest extends BaseChainedSocksProxyTest {
  @Override
  protected ChainedProxyType getSocksProxyType() {
    return ChainedProxyType.SOCKS4;
  }
}
