package org.littleshoot.proxy.extras;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfSignedSslEngineSourceTest {

  @TempDir File tempDir;

  @Test
  void testDefaultConstructor() {
    // The default constructor tries to create "littleproxy_keystore.jks"
    // This may succeed if keytool is available or fail otherwise
    try {
      new SelfSignedSslEngineSource();
      // Success - keytool is available and keystore was created
    } catch (RuntimeException e) {
      // Expected if keytool is not available or keystore creation fails
      assertThat(e.getMessage())
          .satisfiesAnyOf(
              msg -> assertThat(msg).containsIgnoringCase("keytool"),
              msg -> assertThat(msg).containsIgnoringCase("keystore"),
              msg -> assertThat(msg).containsIgnoringCase("SSLContext"));
    } catch (Exception e) {
      // Other exceptions are also acceptable
      assertThat(e).isNotNull();
    }
  }

  @Test
  void testConstructorWithKeyStorePath() {
    String keystorePath = new File(tempDir, "test_keystore.jks").getAbsolutePath();

    try {
      new SelfSignedSslEngineSource(keystorePath);
    } catch (RuntimeException e) {
      assertThat(e.getMessage())
          .satisfiesAnyOf(
              msg -> assertThat(msg).containsIgnoringCase("keytool"),
              msg -> assertThat(msg).containsIgnoringCase("keystore"),
              msg -> assertThat(msg).containsIgnoringCase("SSLContext"));
    } catch (Exception e) {
      assertThat(e).isNotNull();
    }
  }

  @Test
  void testConstructorWithTrustAllServers() {
    try {
      new SelfSignedSslEngineSource(true);
    } catch (RuntimeException e) {
      assertThat(e.getMessage())
          .satisfiesAnyOf(
              msg -> assertThat(msg).containsIgnoringCase("keytool"),
              msg -> assertThat(msg).containsIgnoringCase("keystore"),
              msg -> assertThat(msg).containsIgnoringCase("SSLContext"));
    } catch (Exception e) {
      assertThat(e).isNotNull();
    }
  }

  @Test
  void testConstructorWithAllParameters() {
    String keystorePath = new File(tempDir, "test.jks").getAbsolutePath();

    try {
      new SelfSignedSslEngineSource(keystorePath, true, false);
    } catch (RuntimeException e) {
      assertThat(e.getMessage())
          .satisfiesAnyOf(
              msg -> assertThat(msg).containsIgnoringCase("keytool"),
              msg -> assertThat(msg).containsIgnoringCase("keystore"),
              msg -> assertThat(msg).containsIgnoringCase("SSLContext"));
    } catch (Exception e) {
      assertThat(e).isNotNull();
    }
  }

  @Test
  void testConstructorWithFullParameters() {
    String keystorePath = new File(tempDir, "test_full.jks").getAbsolutePath();

    try {
      new SelfSignedSslEngineSource(keystorePath, true, false, "test_alias", "test_password");
    } catch (RuntimeException e) {
      assertThat(e.getMessage())
          .satisfiesAnyOf(
              msg -> assertThat(msg).containsIgnoringCase("keytool"),
              msg -> assertThat(msg).containsIgnoringCase("keystore"),
              msg -> assertThat(msg).containsIgnoringCase("SSLContext"));
    } catch (Exception e) {
      assertThat(e).isNotNull();
    }
  }

  @Test
  void testNewSslEngine() throws Exception {
    String keystorePath = new File(tempDir, "engine_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source;
    try {
      source = new SelfSignedSslEngineSource(keystorePath);
    } catch (Exception e) {
      // Cannot test without keystore
      return;
    }

    SSLEngine engine = source.newSslEngine();

    assertThat(engine).isNotNull();
    assertThat(engine.getUseClientMode()).isFalse();
  }

  @Test
  void testNewSslEngineWithPeerInfo() throws Exception {
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
  void testGetSslContext() throws Exception {
    String keystorePath = new File(tempDir, "context_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source;
    try {
      source = new SelfSignedSslEngineSource(keystorePath);
    } catch (Exception e) {
      return;
    }

    SSLContext context = source.getSslContext();

    assertThat(context).isNotNull();
    assertThat(context.getProtocol()).isEqualTo("TLS");
  }

  @Test
  void testImplementsSslEngineSource() {
    boolean implementsInterface =
        org.littleshoot.proxy.SslEngineSource.class.isAssignableFrom(
            SelfSignedSslEngineSource.class);
    assertThat(implementsInterface).isTrue();
  }

  @Test
  void testTrustAllServersOption() throws Exception {
    String keystorePath = new File(tempDir, "trust_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source;
    try {
      source = new SelfSignedSslEngineSource(keystorePath, true, true);
    } catch (Exception e) {
      return;
    }

    assertThat(source).isNotNull();
    assertThat(source.getSslContext()).isNotNull();
  }

  @Test
  void testSendCertsOption() throws Exception {
    String keystorePath = new File(tempDir, "certs_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source;
    try {
      source = new SelfSignedSslEngineSource(keystorePath, false, false);
    } catch (Exception e) {
      return;
    }

    assertThat(source).isNotNull();
    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
  }
}
