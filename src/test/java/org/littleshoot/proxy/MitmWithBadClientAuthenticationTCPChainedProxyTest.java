package org.littleshoot.proxy;

import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

import javax.net.ssl.SSLEngine;

import static org.littleshoot.proxy.TransportProtocol.TCP;

/**
 * Tests that clients are authenticated and that if they're missing certs, we
 * get an error.
 */
public final class MitmWithBadClientAuthenticationTCPChainedProxyTest extends MitmWithChainedProxyTest {
    private final SslEngineSource serverSslEngineSource = new SelfSignedSslEngineSource("target/chain_proxy_keystore_1.jks");
    private final SslEngineSource clientSslEngineSource = new SelfSignedSslEngineSource("target/chain_proxy_keystore_1.jks", false, false);

    @Override
    protected boolean expectBadGatewayForEverything() {
        return true;
    }
    
    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withTransportProtocol(TCP)
                .withSslEngineSource(serverSslEngineSource);
    }

    @Override
    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy() {
          @Override
            public boolean requiresEncryption() {
                return true;
            }

            @Override
            public SSLEngine newSslEngine() {
                return clientSslEngineSource.newSslEngine();
            }
        };
    }
}
