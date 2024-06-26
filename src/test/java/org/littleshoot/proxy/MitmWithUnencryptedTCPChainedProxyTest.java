package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.TCP;

public final class MitmWithUnencryptedTCPChainedProxyTest extends MitmWithChainedProxyTest {
    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withTransportProtocol(TCP);
    }
}
