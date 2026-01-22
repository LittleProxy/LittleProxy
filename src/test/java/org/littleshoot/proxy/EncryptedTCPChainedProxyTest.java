package org.littleshoot.proxy;

import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

import javax.net.ssl.SSLEngine;

import static org.littleshoot.proxy.TransportProtocol.TCP;

@Execution(ExecutionMode.SAME_THREAD)
public final class EncryptedTCPChainedProxyTest extends BaseChainedProxyTest {
    private final SslEngineSource sslEngineSource = new SelfSignedSslEngineSource("target/chain_proxy_keystore_1.jks");

    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withSslEngineSource(sslEngineSource);
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
                return sslEngineSource.newSslEngine();
            }
        };
    }
}
