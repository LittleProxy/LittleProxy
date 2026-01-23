package org.littleshoot.proxy;

import javax.net.ssl.SSLEngine;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

/**
 * Tests that when client authentication is not required, it doesn't matter what certs the client
 * sends.
 */
public final class MitmWithClientAuthenticationNotRequiredTCPChainedProxyTest
    extends MitmWithChainedProxyTest {
  private final SslEngineSource serverSslEngineSource =
      new SelfSignedSslEngineSource("target/chain_proxy_keystore_1.jks");
  private final SslEngineSource clientSslEngineSource =
      new SelfSignedSslEngineSource("target/chain_proxy_keystore_1.jks", false, false);

  @Override
  protected HttpProxyServerBootstrap upstreamProxy() {
    return super.upstreamProxy()
        .withSslEngineSource(serverSslEngineSource)
        .withAuthenticateSslClients(false);
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
