package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpObject;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** Tests for ChainedProxy interface. */
class ChainedProxyTest {

  @Test
  void testInterfaceDefinition() {
    assertThat(ChainedProxy.class).isInterface();
  }

  @Test
  void testExtendsSslEngineSource() {
    assertThat(SslEngineSource.class.isAssignableFrom(ChainedProxy.class)).isTrue();
  }

  @Test
  void testHasGetChainedProxyAddressMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("getChainedProxyAddress")).isNotNull();
    assertThat(ChainedProxy.class.getMethod("getChainedProxyAddress").getReturnType())
        .isEqualTo(InetSocketAddress.class);
  }

  @Test
  void testHasGetLocalAddressMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("getLocalAddress")).isNotNull();
    assertThat(ChainedProxy.class.getMethod("getLocalAddress").getReturnType())
        .isEqualTo(InetSocketAddress.class);
  }

  @Test
  void testHasGetTransportProtocolMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("getTransportProtocol")).isNotNull();
    assertThat(ChainedProxy.class.getMethod("getTransportProtocol").getReturnType())
        .isEqualTo(TransportProtocol.class);
  }

  @Test
  void testHasGetChainedProxyTypeMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("getChainedProxyType")).isNotNull();
    assertThat(ChainedProxy.class.getMethod("getChainedProxyType").getReturnType())
        .isEqualTo(ChainedProxyType.class);
  }

  @Test
  void testHasGetUsernameMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("getUsername")).isNotNull();
    assertThat(ChainedProxy.class.getMethod("getUsername").getReturnType()).isEqualTo(String.class);
  }

  @Test
  void testHasGetPasswordMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("getPassword")).isNotNull();
    assertThat(ChainedProxy.class.getMethod("getPassword").getReturnType()).isEqualTo(String.class);
  }

  @Test
  void testHasRequiresEncryptionMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("requiresEncryption")).isNotNull();
    assertThat(ChainedProxy.class.getMethod("requiresEncryption").getReturnType())
        .isEqualTo(boolean.class);
  }

  @Test
  void testHasFilterRequestMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("filterRequest", HttpObject.class)).isNotNull();
    assertThat(ChainedProxy.class.getMethod("filterRequest", HttpObject.class).getReturnType())
        .isEqualTo(void.class);
  }

  @Test
  void testHasConnectionSucceededMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("connectionSucceeded")).isNotNull();
    assertThat(ChainedProxy.class.getMethod("connectionSucceeded").getReturnType())
        .isEqualTo(void.class);
  }

  @Test
  void testHasConnectionFailedMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("connectionFailed", Throwable.class)).isNotNull();
    assertThat(ChainedProxy.class.getMethod("connectionFailed", Throwable.class).getReturnType())
        .isEqualTo(void.class);
  }

  @Test
  void testHasDisconnectedMethod() throws NoSuchMethodException {
    assertThat(ChainedProxy.class.getMethod("disconnected")).isNotNull();
    assertThat(ChainedProxy.class.getMethod("disconnected").getReturnType()).isEqualTo(void.class);
  }
}
