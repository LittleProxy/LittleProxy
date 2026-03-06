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
  void testGetAcceptedIssuers() {
    X509Certificate[] issuers = trustManager.getAcceptedIssuers();

    // Should return null (accepts all issuers)
    assertThat(issuers).isNull();
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
}
