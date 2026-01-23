package org.littleshoot.proxy;

import javax.net.ssl.SSLEngine;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

/** Tests that clients are authenticated and that if they're missing certs, we get an error. */
public final class BadClientAuthenticationTCPChainedProxyTest extends BaseChainedProxyTest {
  private final SslEngineSource serverSslEngineSource =
      new SelfSignedSslEngineSource("target/chain_proxy_keystore_1.jks", false, false);
  private final SslEngineSource clientSslEngineSource =
      new SelfSignedSslEngineSource("target/chain_proxy_keystore_1.jks", false, false);

  @Override
  protected boolean expectBadGatewayForEverything() {
    return true;
  }

  @Override
  protected HttpProxyServerBootstrap upstreamProxy() {
    return super.upstreamProxy().withSslEngineSource(serverSslEngineSource);
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
