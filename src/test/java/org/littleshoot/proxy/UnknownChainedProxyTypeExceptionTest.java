package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UnknownChainedProxyTypeExceptionTest {

  @Test
  void testExtendsRuntimeException() {
    UnknownChainedProxyTypeException exception =
        new UnknownChainedProxyTypeException(ChainedProxyType.HTTP);

    assertThat(exception).isInstanceOf(RuntimeException.class);
  }

  @Test
  void testMessageContainsTypeName() {
    UnknownChainedProxyTypeException exception =
        new UnknownChainedProxyTypeException(ChainedProxyType.SOCKS5);

    assertThat(exception.getMessage()).contains("ChainedProxyType");
    assertThat(exception.getMessage()).contains("SOCKS5");
  }

  @Test
  void testWithAllProxyTypes() {
    for (ChainedProxyType type : ChainedProxyType.values()) {
      UnknownChainedProxyTypeException exception = new UnknownChainedProxyTypeException(type);
      assertThat(exception.getMessage()).contains(type.name());
    }
  }

  @Test
  void testExceptionCanBeThrown() {
    assertThatThrownBy(
            () -> {
              throw new UnknownChainedProxyTypeException(ChainedProxyType.HTTP);
            })
        .isInstanceOf(UnknownChainedProxyTypeException.class)
        .hasMessageContaining("ChainedProxyType");
  }
}
