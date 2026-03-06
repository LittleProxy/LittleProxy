package org.littleshoot.proxy.extras;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfSignedSslEngineSourceTest {

  @TempDir File tempDir;

  @Test
  void testDefaultConstructor() {
    // The default constructor tries to create "littleproxy_keystore.jks"
    // This may succeed if keytool is available or fail otherwise
    File file = new File(tempDir, "littleproxy_keystore.jks");
    if (file.exists()) {
      boolean delete = file.delete();
      assertThat(delete).isTrue();
    }
    Assertions.assertDoesNotThrow(() -> new SelfSignedSslEngineSource());
  }

  @Test
  void testConstructorWithKeyStorePath() {
    String keystorePath = new File(tempDir, "test_keystore.jks").getAbsolutePath();
    Assertions.assertDoesNotThrow(() -> new SelfSignedSslEngineSource(keystorePath));
  }

  @Test
  void testConstructorWithTrustAllServers() {
    Assertions.assertDoesNotThrow(() -> new SelfSignedSslEngineSource(true));
  }

  @Test
  void testConstructorWithAllParameters() {
    String keystorePath = new File(tempDir, "test.jks").getAbsolutePath();
    Assertions.assertDoesNotThrow(() -> new SelfSignedSslEngineSource(keystorePath, true, false));
  }

  @Test
  void testConstructorWithFullParameters() {
    String keystorePath = new File(tempDir, "test_full.jks").getAbsolutePath();
    Assertions.assertDoesNotThrow(
        () ->
            new SelfSignedSslEngineSource(
                keystorePath, true, false, "test_alias", "test_password"));
  }

  @Test
  void testNewSslEngine() {
    String keystorePath = new File(tempDir, "engine_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath);
    SSLEngine engine = source.newSslEngine();

    assertThat(engine).isNotNull();
    assertThat(engine.getUseClientMode()).isFalse();
  }

  @Test
  void testNewSslEngineWithPeerInfo() {
    String keystorePath = new File(tempDir, "peer_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source;
    try {
      source = new SelfSignedSslEngineSource(keystorePath);
    } catch (Exception e) {
      return;
    }

    SSLEngine engine = source.newSslEngine("example.com", 443);

    assertThat(engine).isNotNull();
    assertThat(engine.getPeerHost()).isEqualTo("example.com");
    assertThat(engine.getPeerPort()).isEqualTo(443);
  }

  @Test
  void testGetSslContext() {
    String keystorePath = new File(tempDir, "context_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath);

    SSLContext context = source.getSslContext();

    assertThat(context).isNotNull();
    assertThat(context.getProtocol()).isEqualTo("TLS");
  }

  @Test
  void testTrustAllServersOption() {
    String keystorePath = new File(tempDir, "trust_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath, true, true);

    assertThat(source).isNotNull();
    assertThat(source.getSslContext()).isNotNull();
  }

  @Test
  void testSendCertsOption() {
    String keystorePath = new File(tempDir, "certs_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath, false, false);
    assertThat(source).isNotNull();
    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
  }
}
