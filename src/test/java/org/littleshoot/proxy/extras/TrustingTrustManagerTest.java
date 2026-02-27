package org.littleshoot.proxy.extras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustingTrustManagerTest {

  private TrustingTrustManager trustManager;

  @BeforeEach
  void setUp() {
    trustManager = new TrustingTrustManager();
  }

  @Test
  void testCheckClientTrusted() {
    X509Certificate[] certs = new X509Certificate[0];

    // Should not throw any exception for any client certificate
    assertThatCode(() -> trustManager.checkClientTrusted(certs, "RSA")).doesNotThrowAnyException();
  }

  @Test
  void testCheckServerTrusted() {
    X509Certificate[] certs = new X509Certificate[0];

    // Should not throw any exception for any server certificate
    assertThatCode(() -> trustManager.checkServerTrusted(certs, "RSA")).doesNotThrowAnyException();
  }

  @Test
  void testCheckClientTrustedWithNullCerts() {
    // Should not throw even with null certificates
    assertThatCode(() -> trustManager.checkClientTrusted(null, "RSA")).doesNotThrowAnyException();
  }

  @Test
  void testCheckServerTrustedWithNullCerts() {
    // Should not throw even with null certificates
    assertThatCode(() -> trustManager.checkServerTrusted(null, "RSA")).doesNotThrowAnyException();
  }

  @Test
  void testGetAcceptedIssuers() {
    X509Certificate[] issuers = trustManager.getAcceptedIssuers();

    // Should return null (accepts all issuers)
    assertThat(issuers).isNull();
  }

  @Test
  void testImplementsX509TrustManager() {
    assertThat(trustManager).isInstanceOf(X509TrustManager.class);
  }

  @Test
  void testTrustsAllClients() {
    // Verify behavior: any client is trusted
    boolean exceptionThrown = false;
    try {
      trustManager.checkClientTrusted(new X509Certificate[0], "RSA");
    } catch (Exception e) {
      exceptionThrown = true;
    }
    assertThat(exceptionThrown).isFalse();
  }

  @Test
  void testTrustsAllServers() {
    // Verify behavior: any server is trusted
    boolean exceptionThrown = false;
    try {
      trustManager.checkServerTrusted(new X509Certificate[0], "RSA");
    } catch (Exception e) {
      exceptionThrown = true;
    }
    assertThat(exceptionThrown).isFalse();
  }
}
