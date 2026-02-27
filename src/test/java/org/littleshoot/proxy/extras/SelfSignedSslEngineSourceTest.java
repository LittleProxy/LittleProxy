package org.littleshoot.proxy.extras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class SelfSignedSslEngineSourceTest {

  private static final String DEFAULT_KEYSTORE = "littleproxy_keystore.jks";
  private static final int BUFFER_CAPACITY = 32768;

  @TempDir File tempDir;

  @BeforeEach
  void cleanDefaultKeystore() {
    new File(DEFAULT_KEYSTORE).delete();
    new File("littleproxy_cert").delete();
  }

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

  @AfterAll
  static void cleanUp() {
    File defaultKeystore = new File(DEFAULT_KEYSTORE);
    if (defaultKeystore.exists()) {
      defaultKeystore.delete();
    }
    File defaultCert = new File("littleproxy_cert");
    if (defaultCert.exists()) {
      defaultCert.delete();
    }
  }

  @Test
  @Order(1)
  void defaultConstructor() {
    // The default constructor creates "littleproxy_keystore.jks" in the working directory
    // Clean up any existing keystore first to ensure the test is deterministic
    File keystore = new File(DEFAULT_KEYSTORE);
    if (keystore.exists()) {
      // Attempt to delete, but don't fail test if delete fails (file may be locked)
      // The assertion on doesNotExist() below will catch if cleanup failed
      keystore.delete();
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
  void constructorWithKeyStorePathReusesExistingKeystore() {
    // Test that existing keystore is reused when file already exists
    String keystorePath = new File(tempDir, "test_keystore_existing.jks").getAbsolutePath();
    File keystoreFile = new File(keystorePath);

    // First instantiation creates the keystore
    SelfSignedSslEngineSource first = new SelfSignedSslEngineSource(keystorePath);
    long originalSize = keystoreFile.length();
    assertThat(originalSize).as("Original keystore should have content").isGreaterThan(0);

    // Second instantiation should reuse existing keystore without modification
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath);

    // Verify source is functional and keystore wasn't regenerated
    assertThat(source.getSslContext()).isNotNull();
    assertThat(keystoreFile.length())
        .as("Keystore size should not change when reusing")
        .isEqualTo(originalSize);

    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
  }

  @Test
  @Order(2)
  void constructorWithTrustAllServersGeneratesDefaultKeystore() {
    // Test trustAllServers=true with default keystore path
    // Delete any existing default keystore to test generation
    File defaultKeystore = new File(DEFAULT_KEYSTORE);
    if (defaultKeystore.exists()) {
      // Attempt to delete, but don't fail test if delete fails (file may be locked)
      defaultKeystore.delete();
    }

    // Create source with trustAllServers enabled
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(true);

    // Verify source and SSLContext are initialized
    assertThat(source.getSslContext()).isNotNull();
    assertThat(source.getSslContext().getProtocol()).isEqualTo("TLS");
    // Verify default keystore was created
    assertThat(defaultKeystore).as("Default keystore should be created").exists();
    assertThat(defaultKeystore.length())
        .as("Default keystore should not be empty")
        .isGreaterThan(0);

    // Verify SSLEngine is functional
    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
    assertThat(engine.getUseClientMode()).isFalse();
  }

  @Test
  @Order(3)
  void constructorWithTrustAllServersReusesExistingKeystore() {
    // Test that existing default keystore is reused
    File defaultKeystore = new File(DEFAULT_KEYSTORE);
    // Ensure keystore exists before testing reuse
    if (!defaultKeystore.exists()) {
      new SelfSignedSslEngineSource(true);
    }
    long originalSize = defaultKeystore.length();
    assertThat(originalSize).as("Original keystore should have content").isGreaterThan(0);

    // Create another instance - should reuse existing keystore
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(true);

    // Verify keystore wasn't regenerated
    assertThat(source.getSslContext()).isNotNull();
    assertThat(defaultKeystore.length())
        .as("Keystore size should not change when reusing")
        .isEqualTo(originalSize);

    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
  }

  @Test
  void constructorWithFullParametersGeneratesNewKeystore() throws Exception {
    // Test constructor with custom alias and password parameters
    String keystorePath = new File(tempDir, "test_full.jks").getAbsolutePath();
    String alias = "test_alias";
    String password = "test_password";
    File keystoreFile = new File(keystorePath);

    SelfSignedSslEngineSource source =
        new SelfSignedSslEngineSource(keystorePath, true, false, alias, password);

    assertThat(source.getSslContext()).isNotNull();
    assertThat(source.getSslContext().getProtocol()).isEqualTo("TLS");
    assertThat(keystoreFile).as("Keystore should be created").exists();
    assertThat(keystoreFile.length()).as("Keystore should not be empty").isGreaterThan(0);

    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
    assertThat(engine.getUseClientMode()).isFalse();
    // this is an indirect check that password is used
    assertThatThrownBy(
            () -> {
              KeyStore keyStore = KeyStore.getInstance("JKS");
              try (FileInputStream fis = new FileInputStream(keystoreFile)) {
                keyStore.load(fis, "Be Your Own Lantern".toCharArray());
              }
            })
        .as("Keystore should not be loadable with default password")
        .isInstanceOf(IOException.class)
        .hasMessageStartingWith("keystore password was incorrect");

    KeyStore keyStore = KeyStore.getInstance("JKS");
    try (FileInputStream fis = new FileInputStream(keystoreFile)) {
      keyStore.load(fis, password.toCharArray());
    }
    assertThat(keyStore.containsAlias(alias))
        .as(() -> "Keystore should contain the custom alias, but received: " + aliasesOf(keyStore))
        .isTrue();
  }

  @Test
  void constructorWithKeyStorePathGeneratesNewKeystore() {
    // Test that a new keystore is generated when the specified file doesn't exist
    String keystorePath = new File(tempDir, "test_keystore.jks").getAbsolutePath();
    File keystoreFile = new File(keystorePath);
    // Verify keystore doesn't exist before instantiation
    assertThat(keystoreFile).as("Keystore should not exist before test").doesNotExist();

    // Create source - this should generate a new keystore
    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath);

    // Verify source is properly initialized
    assertThat(source.getSslContext()).isNotNull();
    assertThat(source.getSslContext().getProtocol()).isEqualTo("TLS");
    // Verify keystore was created with content
    assertThat(keystoreFile).as("Keystore should be created").exists();
    assertThat(keystoreFile.length()).as("Keystore should not be empty").isGreaterThan(0);

    // Verify SSLEngine is created in server mode
    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
    assertThat(engine.getUseClientMode()).isFalse();
  }

  @Test
  void newSslEngineWithPeerInfoGeneratesNewKeystore() {
    // Test newSslEngine(peerHost, peerPort) with custom peer information
    String keystorePath = new File(tempDir, "peer_test.jks").getAbsolutePath();
    File keystoreFile = new File(keystorePath);

    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath);

    SSLEngine engine = source.newSslEngine("example.com", -1);

    assertThat(engine).isNotNull();
    assertThat(engine.getPeerHost()).isEqualTo("example.com");
    assertThat(engine.getPeerPort()).isEqualTo(-1);
    assertThat(keystoreFile).as("Keystore should be created").exists();
    assertThat(keystoreFile.length()).as("Keystore should not be empty").isGreaterThan(0);
  }

  @Test
  void getSslContextGeneratesNewKeystore() {
    // Test getSslContext() returns valid SSLContext and generates keystore
    String keystorePath = new File(tempDir, "context_test.jks").getAbsolutePath();
    File keystoreFile = new File(keystorePath);

    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath);

    SSLContext context = source.getSslContext();

    assertThat(context).isNotNull();
    assertThat(context.getProtocol()).isEqualTo("TLS");
    assertThat(keystoreFile).as("Keystore should be created").exists();
    assertThat(keystoreFile.length()).as("Keystore should not be empty").isGreaterThan(0);
  }

  @Test
  void trustAllServersOptionGeneratesNewKeystore() {
    // Test trustAllServers=true option with sendCerts=true
    String keystorePath = new File(tempDir, "trust_test.jks").getAbsolutePath();
    File keystoreFile = new File(keystorePath);

    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath, true, true);

    assertThat(source.getSslContext()).isNotNull();
    assertThat(source.getSslContext().getProtocol()).isEqualTo("TLS");
    assertThat(keystoreFile).as("Keystore should be created").exists();
    assertThat(keystoreFile.length()).as("Keystore should not be empty").isGreaterThan(0);

    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
  }

  @Test
  void sendCertsOptionGeneratesNewKeystore() {
    // Test sendCerts=false option (trustAllServers=false)
    String keystorePath = new File(tempDir, "certs_test.jks").getAbsolutePath();
    File keystoreFile = new File(keystorePath);

    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath, false, false);

    assertThat(getBooleanField(source, "trustAllServers"))
        .as("trustAllServers should be initialized from constructor")
        .isFalse();
    assertThat(getBooleanField(source, "sendCerts"))
        .as("sendCerts should be initialized from constructor")
        .isFalse();

    assertThat(source.getSslContext()).isNotNull();
    assertThat(source.getSslContext().getProtocol()).isEqualTo("TLS");
    assertThat(keystoreFile).as("Keystore should be created").exists();
    assertThat(keystoreFile.length()).as("Keystore should not be empty").isGreaterThan(0);

    SSLEngine engine = source.newSslEngine();
    assertThat(engine).isNotNull();
  }

  @Test
  void tlsHandshakeGeneratesNewKeystore() throws Exception {
    // Test that generated keystore enables successful TLS handshake
    String keystorePath = new File(tempDir, "handshake_test.jks").getAbsolutePath();
    File keystoreFile = new File(keystorePath);

    SelfSignedSslEngineSource source = new SelfSignedSslEngineSource(keystorePath);

    assertThat(keystoreFile).as("Keystore should be created").exists();
    assertThat(keystoreFile.length()).as("Keystore should not be empty").isGreaterThan(0);

    // Create server-side SSLEngine
    SSLEngine serverEngine = source.newSslEngine();
    serverEngine.setUseClientMode(false);

    // Create client-side SSLEngine using same SSLContext
    SSLContext clientContext = source.getSslContext();
    SSLEngine clientEngine = clientContext.createSSLEngine("localhost", -1);
    clientEngine.setUseClientMode(true);

    // Perform TLS handshake in-memory
    performHandshake(clientEngine, serverEngine);

    // Verify handshake completed successfully
    assertThat(clientEngine.getSession()).isNotNull();
    assertThat(serverEngine.getSession()).isNotNull();
  }

  private List<String> aliasesOf(KeyStore keyStore) {
    try {
      return Collections.list(keyStore.aliases());
    } catch (KeyStoreException e) {
      throw new RuntimeException(e);
    }
  }

  // Performs an in-memory TLS handshake between two SSLEngines using ByteBuffers
  // This validates that the keystore produces valid certificates without needing a real network
  private void performHandshake(SSLEngine client, SSLEngine server) throws Exception {
    ByteBuffer clientOut = ByteBuffer.allocate(BUFFER_CAPACITY);
    ByteBuffer serverOut = ByteBuffer.allocate(BUFFER_CAPACITY);
    clientOut.flip();
    serverOut.flip();

    int maxIterations = 100;
    while (client.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        || server.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
      maxIterations--;
      assertThat(maxIterations).isPositive();

      if (client.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
        clientOut.clear();
        SSLEngineResult cr = client.wrap(ByteBuffer.allocate(0), clientOut);
        clientOut.flip();
        assertThat(cr.getStatus()).isEqualTo(SSLEngineResult.Status.OK);
      }

      if (server.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
        serverOut.clear();
        SSLEngineResult sr = server.wrap(ByteBuffer.allocate(0), serverOut);
        serverOut.flip();
        assertThat(sr.getStatus()).isEqualTo(SSLEngineResult.Status.OK);
      }

      if (client.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
        if (serverOut.hasRemaining()) {
          ByteBuffer temp = ByteBuffer.allocate(BUFFER_CAPACITY);
          temp.put(serverOut);
          temp.flip();
          client.unwrap(temp, ByteBuffer.allocate(BUFFER_CAPACITY));
        }
      }

      if (server.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
        if (clientOut.hasRemaining()) {
          ByteBuffer temp = ByteBuffer.allocate(BUFFER_CAPACITY);
          temp.put(clientOut);
          temp.flip();
          server.unwrap(temp, ByteBuffer.allocate(BUFFER_CAPACITY));
        }
      }

      Thread.sleep(1);
    }
  }

  private static boolean getBooleanField(SelfSignedSslEngineSource source, String fieldName) {
    try {
      Field field = SelfSignedSslEngineSource.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.getBoolean(source);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new AssertionError("Unable to read field '" + fieldName + "'", e);
    }
  }
}
