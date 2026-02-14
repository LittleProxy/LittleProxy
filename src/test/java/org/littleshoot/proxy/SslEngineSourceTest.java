package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;

/** Tests for SslEngineSource interface. */
class SslEngineSourceTest {

  @Test
  void testInterfaceDefinition() {
    assertThat(SslEngineSource.class).isInterface();
  }

  @Test
  void testHasNewSslEngineMethod() throws NoSuchMethodException {
    assertThat(SslEngineSource.class.getMethod("newSslEngine")).isNotNull();
    assertThat(SslEngineSource.class.getMethod("newSslEngine").getReturnType())
        .isEqualTo(SSLEngine.class);
  }

  @Test
  void testHasNewSslEngineWithPeerInfoMethod() throws NoSuchMethodException {
    assertThat(SslEngineSource.class.getMethod("newSslEngine", String.class, int.class))
        .isNotNull();
    assertThat(
            SslEngineSource.class
                .getMethod("newSslEngine", String.class, int.class)
                .getReturnType())
        .isEqualTo(SSLEngine.class);
  }
}
