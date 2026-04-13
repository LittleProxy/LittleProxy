package org.littleshoot.proxy.extras;

import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNullElse;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Security;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.littleshoot.proxy.SslEngineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic {@link SslEngineSource} for testing. The {@link SSLContext} uses self-signed certificates
 * that are generated lazily if the given key store file doesn't yet exist.
 */
public class SelfSignedSslEngineSource implements SslEngineSource {
  private static final Logger LOG = LoggerFactory.getLogger(SelfSignedSslEngineSource.class);

  private static final String PROTOCOL = "TLS";

  private final String alias;
  private final String password;
  private final String keyStoreFile;
  private final boolean trustAllServers;
  private final boolean sendCerts;

  private SSLContext sslContext;

  public SelfSignedSslEngineSource(
      String keyStorePath,
      boolean trustAllServers,
      boolean sendCerts,
      String alias,
      String password) {
    this.trustAllServers = trustAllServers;
    this.sendCerts = sendCerts;
    this.keyStoreFile = keyStorePath;
    this.alias = alias;
    this.password = password;
    initializeSSLContext();
  }

  public SelfSignedSslEngineSource(
      String keyStorePath, boolean trustAllServers, boolean sendCerts) {
    this(keyStorePath, trustAllServers, sendCerts, "littleproxy", "Be Your Own Lantern");
  }

  public SelfSignedSslEngineSource(String keyStorePath) {
    this(keyStorePath, false, true);
  }

  public SelfSignedSslEngineSource(boolean trustAllServers) {
    this(trustAllServers, true);
  }

  public SelfSignedSslEngineSource(boolean trustAllServers, boolean sendCerts) {
    this("littleproxy_keystore.jks", trustAllServers, sendCerts);
  }

  public SelfSignedSslEngineSource() {
    this(false);
  }

  @Override
  public SSLEngine newSslEngine() {
    return sslContext.createSSLEngine();
  }

  @Override
  public SSLEngine newSslEngine(String peerHost, int peerPort) {
    return sslContext.createSSLEngine(peerHost, peerPort);
  }

  public SSLContext getSslContext() {
    return sslContext;
  }

  private void initializeKeyStore(File keyStoreLocalFile) {
    initializeKeyStore(keyStoreLocalFile, "littleproxy_cert");
  }

  private void initializeKeyStore(File keyStoreLocalFile, String certificateFileName) {
    File keyStoreLocalAbsoluteFile = keyStoreLocalFile.getAbsoluteFile();

    nativeCall(
        "keytool",
        "-genkey",
        "-alias",
        alias,
        "-keysize",
        "4096",
        "-validity",
        "36500",
        "-keyalg",
        "RSA",
        "-dname",
        "CN=littleproxy",
        "-keypass",
        password,
        "-storepass",
        password,
        "-keystore",
        keyStoreLocalAbsoluteFile.getPath());

    LOG.info("Generated LittleProxy keystore in {}", keyStoreLocalAbsoluteFile);

    Path certificateFile = Paths.get(keyStoreLocalAbsoluteFile.getParent(), certificateFileName);
    nativeCall(
        "keytool",
        "-exportcert",
        "-alias",
        alias,
        "-keystore",
        keyStoreLocalAbsoluteFile.getPath(),
        "-storepass",
        password,
        "-file",
        certificateFile.toString());
    LOG.info("Generated LittleProxy certificate in {}", certificateFile);
  }

  private void initializeSSLContext() {
    String algorithm =
        requireNonNullElse(Security.getProperty("ssl.KeyManagerFactory.algorithm"), "SunX509");

    try {
      final KeyStore ks = loadKeyStore();

      // Set up key manager factory to use our key store
      final KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
      kmf.init(ks, password.toCharArray());

      // Set up a trust manager factory to use our key store
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(algorithm);
      tmf.init(ks);

      TrustManager[] trustManagers = createTrustManagers(tmf);
      KeyManager[] keyManagers = sendCerts ? kmf.getKeyManagers() : new KeyManager[0];

      // Initialize the SSLContext to work with our key managers.
      sslContext = SSLContext.getInstance(PROTOCOL);
      sslContext.init(keyManagers, trustManagers, null);
    } catch (IOException | GeneralSecurityException e) {
      throw new RuntimeException("Failed to initialize the server-side SSLContext", e);
    }
  }

  private TrustManager[] createTrustManagers(TrustManagerFactory tmf) {
    return trustAllServers
        ? new TrustManager[] {new TrustingTrustManager()}
        : tmf.getTrustManagers();
  }

  private KeyStore loadKeyStore() throws IOException, GeneralSecurityException {
    URL resourceUrl = getClass().getResource(keyStoreFile);
    if (resourceUrl != null) {
      return loadKeyStore(resourceUrl);
    } else {
      File keyStoreLocalFile = new File(keyStoreFile);
      if (!keyStoreLocalFile.isFile()) {
        initializeKeyStore(keyStoreLocalFile);
      }
      return loadKeyStore(keyStoreLocalFile.toURI().toURL());
    }
  }

  private KeyStore loadKeyStore(URL url) throws IOException, GeneralSecurityException {
    KeyStore keyStore = KeyStore.getInstance("JKS");
    try (InputStream is = url.openStream()) {
      keyStore.load(is, password.toCharArray());
    }
    LOG.debug("Loaded LittleProxy keystore from {}", url);
    return keyStore;
  }

  private void nativeCall(final String... commands) {
    long start = nanoTime();
    LOG.info("Running '{}'", asList(commands));
    final ProcessBuilder pb = new ProcessBuilder(commands);
    // Merge stderr into stdout so we only need to read one stream
    pb.redirectErrorStream(true);
    try {
      final Process process = pb.start();
      byte[] data;
      try (InputStream is = process.getInputStream()) {
        data = ByteStreams.toByteArray(is);
      }
      int exitCode = process.waitFor();
      String dataAsString = new String(data, UTF_8);
      LOG.info(
          "Completed native call '{}' in {} ms (exit: {})\nResponse: '{}'",
          asList(commands),
          duration(start),
          exitCode,
          dataAsString);
    } catch (IOException e) {
      LOG.error("Error running commands {} after {} ms", asList(commands), duration(start), e);
    } catch (InterruptedException e) {
      LOG.error("Error running commands {} after {} ms", asList(commands), duration(start), e);
      Thread.currentThread().interrupt();
    }
  }

  private long duration(long start) {
    return NANOSECONDS.toMillis(nanoTime() - start);
  }
}
