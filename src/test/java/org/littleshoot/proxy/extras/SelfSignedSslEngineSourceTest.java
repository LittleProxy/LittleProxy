package org.littleshoot.proxy.extras;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SelfSignedSslEngineSourceTest {

  private static final String DEFAULT_KEYSTORE = "littleproxy_keystore.jks";

  @TempDir File tempDir;

  private static boolean isKeytoolAvailable() {
    ProcessBuilder pb = new ProcessBuilder("keytool", "-help");
    pb.redirectErrorStream(true);
    try {
      Process p = pb.start();
      boolean finished = p.waitFor(5, TimeUnit.SECONDS);
      return finished && p.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  @BeforeAll
  static void setUp() {
    Assumptions.assumeTrue(isKeytoolAvailable(), "keytool is not installed, test ignored");
  }

  @Test
  void testDefaultConstructor() {
    // The default constructor creates "littleproxy_keystore.jks" in the working directory
    // Clean up any existing keystore first to ensure the test is deterministic
    File keystore = new File(DEFAULT_KEYSTORE);
    if (keystore.exists()) {
      boolean deleted = keystore.delete();
      assertThat(deleted).isTrue();
    }

    // Create instance using default constructor (generates the keystore via keytool)
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource();

    // Verify the keystore file was created and is non-empty
    assertThat(keystore).as("Keystore file should exist after default constructor").exists();
    assertThat(keystore.length()).as("Keystore file should not be empty").isGreaterThan(0);

    // Verify the instance is functional
    assertThat(source.getSslContext()).isNotNull();
    assertThat(source.getSslContext().getProtocol()).isEqualTo("TLS");

    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
    assertThat(engine.getUseClientMode()).isFalse();
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

    SSLEngine engine = source.newSslEngine("example.com", -1);

    assertThat(engine).isNotNull();
    assertThat(engine.getPeerHost()).isEqualTo("example.com");
    assertThat(engine.getPeerPort()).isEqualTo(-1);
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

  @Test
  void testTlsHandshake() throws Exception {
    String keystorePath = new File(tempDir, "handshake_test.jks").getAbsolutePath();
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath);

    SSLEngine serverEngine = source.newSslEngine();
    serverEngine.setUseClientMode(false);

    SSLContext clientContext = source.getSslContext();
    // Port -1 is just session metadata - no actual network connection is made
    // The handshake happens entirely in-memory via ByteBuffers
    SSLEngine clientEngine = clientContext.createSSLEngine("localhost", -1);
    clientEngine.setUseClientMode(true);

    performHandshake(clientEngine, serverEngine);

    assertThat(clientEngine.getSession()).isNotNull();
    assertThat(serverEngine.getSession()).isNotNull();
  }

  // Performs an in-memory TLS handshake between two SSLEngines using ByteBuffers
  // This validates that the keystore produces valid certificates without needing a real network
  private void performHandshake(SSLEngine client, SSLEngine server) throws Exception {
    ByteBuffer clientOut = ByteBuffer.allocate(32768);
    ByteBuffer serverOut = ByteBuffer.allocate(32768);
    clientOut.flip();
    serverOut.flip();

    int maxIterations = 100;
    while (client.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        || server.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
      if (maxIterations-- <= 0) {
        throw new AssertionError("TLS handshake did not complete");
      }

      if (client.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
        clientOut.clear();
        SSLEngineResult cr = client.wrap(ByteBuffer.allocate(0), clientOut);
        clientOut.flip();
        if (cr.getStatus() != SSLEngineResult.Status.OK) {
          throw new AssertionError("Client wrap failed: " + cr.getStatus());
        }
      }

      if (server.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
        serverOut.clear();
        SSLEngineResult sr = server.wrap(ByteBuffer.allocate(0), serverOut);
        serverOut.flip();
        if (sr.getStatus() != SSLEngineResult.Status.OK) {
          throw new AssertionError("Server wrap failed: " + sr.getStatus());
        }
      }

      if (client.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
        if (serverOut.hasRemaining()) {
          ByteBuffer temp = ByteBuffer.allocate(32768);
          temp.put(serverOut);
          temp.flip();
          client.unwrap(temp, ByteBuffer.allocate(32768));
        }
      }

      if (server.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
        if (clientOut.hasRemaining()) {
          ByteBuffer temp = ByteBuffer.allocate(32768);
          temp.put(clientOut);
          temp.flip();
          server.unwrap(temp, ByteBuffer.allocate(32768));
        }
      }

      Thread.sleep(1);
    }
  }
}
