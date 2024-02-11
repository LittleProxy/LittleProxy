package org.littleshoot.proxy;

public final class Socks5ChainedProxyTest extends BaseChainedSocksProxyTest {
    @Override
    protected ChainedProxyType getSocksProxyType() {
        return ChainedProxyType.SOCKS5;
    }
}
