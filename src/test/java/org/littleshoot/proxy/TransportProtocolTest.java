package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransportProtocolTest {

  @Test
  void testEnumValues() {
    assertThat(TransportProtocol.values()).hasSize(1);
    assertThat(TransportProtocol.values()).contains(TransportProtocol.TCP);
  }

  @Test
  void testTcp() {
    assertThat(TransportProtocol.TCP).isNotNull();
    assertThat(TransportProtocol.TCP.name()).isEqualTo("TCP");
  }

  @Test
  void testValueOf() {
    assertThat(TransportProtocol.valueOf("TCP")).isEqualTo(TransportProtocol.TCP);
  }
}
