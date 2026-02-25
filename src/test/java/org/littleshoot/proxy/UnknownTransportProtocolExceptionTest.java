package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UnknownTransportProtocolExceptionTest {

  @Test
  void testExtendsRuntimeException() {
    UnknownTransportProtocolException exception =
        new UnknownTransportProtocolException(TransportProtocol.TCP);

    assertThat(exception).isInstanceOf(RuntimeException.class);
  }

  @Test
  void testMessageContainsProtocolName() {
    UnknownTransportProtocolException exception =
        new UnknownTransportProtocolException(TransportProtocol.TCP);

    assertThat(exception.getMessage()).contains("TransportProtocol");
    assertThat(exception.getMessage()).contains("TCP");
  }

  @Test
  void testWithAllProtocols() {
    for (TransportProtocol protocol : TransportProtocol.values()) {
      UnknownTransportProtocolException exception = new UnknownTransportProtocolException(protocol);
      assertThat(exception.getMessage()).contains(protocol.name());
    }
  }

  @Test
  void testExceptionCanBeThrown() {
    assertThatThrownBy(
            () -> {
              throw new UnknownTransportProtocolException(TransportProtocol.TCP);
            })
        .isInstanceOf(UnknownTransportProtocolException.class)
        .hasMessageContaining("TransportProtocol");
  }
}
