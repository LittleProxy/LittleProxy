package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for ProxyAuthenticator interface. */
class ProxyAuthenticatorTest {

  @Test
  void testInterfaceDefinition() {
    assertThat(ProxyAuthenticator.class).isInterface();
  }

  @Test
  void testHasAuthenticateMethod() throws NoSuchMethodException {
    assertThat(ProxyAuthenticator.class.getMethod("authenticate", String.class, String.class))
        .isNotNull();
    assertThat(
            ProxyAuthenticator.class
                .getMethod("authenticate", String.class, String.class)
                .getReturnType())
        .isEqualTo(boolean.class);
  }

  @Test
  void testHasGetRealmMethod() throws NoSuchMethodException {
    assertThat(ProxyAuthenticator.class.getMethod("getRealm")).isNotNull();
    assertThat(ProxyAuthenticator.class.getMethod("getRealm").getReturnType())
        .isEqualTo(String.class);
  }

  @Test
  void testSimpleImplementation() {
    ProxyAuthenticator authenticator =
        new ProxyAuthenticator() {
          @Override
          public boolean authenticate(String userName, String password) {
            return "admin".equals(userName) && "secret".equals(password);
          }

          @Override
          public String getRealm() {
            return "Test Realm";
          }
        };

    assertThat(authenticator.authenticate("admin", "secret")).isTrue();
    assertThat(authenticator.authenticate("admin", "wrong")).isFalse();
    assertThat(authenticator.authenticate("wrong", "secret")).isFalse();
    assertThat(authenticator.getRealm()).isEqualTo("Test Realm");
  }

  @Test
  void testNullRealmImplementation() {
    ProxyAuthenticator authenticator =
        new ProxyAuthenticator() {
          @Override
          public boolean authenticate(String userName, String password) {
            return true;
          }

          @Override
          public String getRealm() {
            return null;
          }
        };

    assertThat(authenticator.getRealm()).isNull();
  }
}
