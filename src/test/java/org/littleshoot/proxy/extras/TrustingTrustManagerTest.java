package org.littleshoot.proxy.extras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;

class TrustingTrustManagerTest {

  private final TrustingTrustManager trustManager = new TrustingTrustManager();

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
  void testCheckClientTrustedWithNullAuthType() {
    // Should not throw even with null auth type
    assertThatCode(() -> trustManager.checkClientTrusted(new X509Certificate[0], null))
        .doesNotThrowAnyException();
  }

  @Test
  void testCheckServerTrustedWithNullAuthType() {
    // Should not throw even with null auth type
    assertThatCode(() -> trustManager.checkServerTrusted(new X509Certificate[0], null))
        .doesNotThrowAnyException();
  }

  @Test
  void testCheckClientTrustedWithBothNull() {
    // Should not throw even when both parameters are null
    assertThatCode(() -> trustManager.checkClientTrusted(null, null)).doesNotThrowAnyException();
  }

  @Test
  void testCheckServerTrustedWithBothNull() {
    // Should not throw even when both parameters are null
    assertThatCode(() -> trustManager.checkServerTrusted(null, null)).doesNotThrowAnyException();
  }

  @Test
  void testGetAcceptedIssuers() {
    // Should return null (accepts all issuers)
    X509Certificate[] issuers = trustManager.getAcceptedIssuers();
    assertThat(issuers).isNull();
  }

  @Test
  void testGetAcceptedIssuersReturnsNullConsistently() {
    // Verify getAcceptedIssuers consistently returns null across multiple calls
    assertThat(trustManager.getAcceptedIssuers()).isNull();
    assertThat(trustManager.getAcceptedIssuers()).isNull();
    assertThat(trustManager.getAcceptedIssuers()).isNull();
  }

  @Test
  void testTrustsAllClients() {
    assertThatCode(() -> trustManager.checkClientTrusted(new X509Certificate[0], "RSA"))
        .doesNotThrowAnyException();
  }

  @Test
  void testTrustsAllServers() {
    assertThatCode(() -> trustManager.checkServerTrusted(new X509Certificate[0], "RSA"))
        .doesNotThrowAnyException();
  }

  @Test
  void testTrustsWithVariousAuthTypes() {
    // Test with various authentication types
    X509Certificate[] certs = new X509Certificate[0];

    assertThatCode(() -> trustManager.checkClientTrusted(certs, "RSA")).doesNotThrowAnyException();
    assertThatCode(() -> trustManager.checkClientTrusted(certs, "DSA")).doesNotThrowAnyException();
    assertThatCode(() -> trustManager.checkClientTrusted(certs, "EC")).doesNotThrowAnyException();
    assertThatCode(() -> trustManager.checkClientTrusted(certs, "DiffieHellman"))
        .doesNotThrowAnyException();
    assertThatCode(() -> trustManager.checkClientTrusted(certs, "")).doesNotThrowAnyException();

    assertThatCode(() -> trustManager.checkServerTrusted(certs, "RSA")).doesNotThrowAnyException();
    assertThatCode(() -> trustManager.checkServerTrusted(certs, "DSA")).doesNotThrowAnyException();
    assertThatCode(() -> trustManager.checkServerTrusted(certs, "EC")).doesNotThrowAnyException();
    assertThatCode(() -> trustManager.checkServerTrusted(certs, "DiffieHellman"))
        .doesNotThrowAnyException();
    assertThatCode(() -> trustManager.checkServerTrusted(certs, "")).doesNotThrowAnyException();
  }

  @Test
  void testMultipleCallsToCheckClientTrusted() {
    // Verify multiple calls don't cause issues
    X509Certificate[] certs = new X509Certificate[0];

    assertThatCode(
            () -> {
              trustManager.checkClientTrusted(certs, "RSA");
              trustManager.checkClientTrusted(certs, "RSA");
              trustManager.checkClientTrusted(certs, "RSA");
            })
        .doesNotThrowAnyException();
  }

  @Test
  void testMultipleCallsToCheckServerTrusted() {
    // Verify multiple calls don't cause issues
    X509Certificate[] certs = new X509Certificate[0];

    assertThatCode(
            () -> {
              trustManager.checkServerTrusted(certs, "RSA");
              trustManager.checkServerTrusted(certs, "RSA");
              trustManager.checkServerTrusted(certs, "RSA");
            })
        .doesNotThrowAnyException();
  }

  @Test
  void testTrustManagerInstanceIsNotNull() {
    // Basic sanity check that the instance exists
    assertThat(trustManager).isNotNull();
  }
}
