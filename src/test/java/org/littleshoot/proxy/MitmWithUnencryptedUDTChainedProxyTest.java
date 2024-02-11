package org.littleshoot.proxy;

import org.junit.jupiter.api.BeforeAll;

import static org.littleshoot.proxy.TestUtils.disableOnMac;
import static org.littleshoot.proxy.TransportProtocol.UDT;

public final class MitmWithUnencryptedUDTChainedProxyTest extends MitmWithChainedProxyTest {
    @BeforeAll
    static void beforeClass() {
        disableOnMac();
    }

    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withTransportProtocol(UDT);
    }

    @Override
    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy() {
            @Override
            public TransportProtocol getTransportProtocol() {
                return UDT;
            }
        };
    }
}
