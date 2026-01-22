package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.TCP;

public final class UnencryptedTCPChainedProxyTest extends BaseChainedProxyTest {
    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy();
    }
}
