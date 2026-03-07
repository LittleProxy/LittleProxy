package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChainedProxyTypeTest {

  @Test
  void testEnumValues() {
    assertThat(ChainedProxyType.values()).hasSize(3);
    assertThat(ChainedProxyType.values())
        .contains(ChainedProxyType.HTTP, ChainedProxyType.SOCKS4, ChainedProxyType.SOCKS5);
  }

  @Test
  void testHttp() {
    assertThat(ChainedProxyType.HTTP).isNotNull();
    assertThat(ChainedProxyType.HTTP.name()).isEqualTo("HTTP");
  }

  @Test
  void testSocks4() {
    assertThat(ChainedProxyType.SOCKS4).isNotNull();
    assertThat(ChainedProxyType.SOCKS4.name()).isEqualTo("SOCKS4");
  }

  @Test
  void testSocks5() {
    assertThat(ChainedProxyType.SOCKS5).isNotNull();
    assertThat(ChainedProxyType.SOCKS5.name()).isEqualTo("SOCKS5");
  }

  @Test
  void testValueOf() {
    assertThat(ChainedProxyType.valueOf("HTTP")).isEqualTo(ChainedProxyType.HTTP);
    assertThat(ChainedProxyType.valueOf("SOCKS4")).isEqualTo(ChainedProxyType.SOCKS4);
    assertThat(ChainedProxyType.valueOf("SOCKS5")).isEqualTo(ChainedProxyType.SOCKS5);
  }
}
