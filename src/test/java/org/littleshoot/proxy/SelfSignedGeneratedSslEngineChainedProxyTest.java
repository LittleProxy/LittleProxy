package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

public final class SelfSignedGeneratedSslEngineChainedProxyTest extends BaseChainedProxyTest {

  @TempDir private File temporaryFolder;

  private SslEngineSource sslEngineSource;

  @Override
  protected void setUp() throws IOException {
    String keyStorePath = temporaryFolder.toPath().resolve("chain_proxy_keystore.jks").toString();
    sslEngineSource =
        new SelfSignedSslEngineSource(
            keyStorePath, false, true, "littleproxy", "Be Your Own Lantern");
    super.setUp();
  }

  @Test
  public void testKeyStoreGeneratedAtProvidedPath() {
    File keyStoreFile = temporaryFolder.toPath().resolve("chain_proxy_keystore.jks").toFile();
    assertThat(keyStoreFile.exists()).isTrue();
  }

  @Test
  public void testCertExportedToKeyStoreDirectory() {
    File certFile = temporaryFolder.toPath().resolve("littleproxy_cert").toFile();
    assertThat(certFile.exists()).isTrue();
  }

  @Override
  protected HttpProxyServerBootstrap upstreamProxy() {
    return super.upstreamProxy().withSslEngineSource(sslEngineSource);
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
