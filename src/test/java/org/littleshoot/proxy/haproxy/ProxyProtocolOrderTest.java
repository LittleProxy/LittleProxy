package org.littleshoot.proxy.haproxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.haproxy.HAProxyMessage;
import org.junit.jupiter.api.Test;

/**
 * Verifies that LittleProxy's inbound pipeline decodes the PROXY protocol header performing the TLS
 * handshake.
 */
public final class ProxyProtocolOrderTest extends BaseProxyProtocolTest {

  @Override
  protected boolean useTlsInbound() {
    return true;
  }

  @Test
  void proxyProtocolIsDecodedBeforeTlsOnInbound() throws Exception {
    setup(true, true);

    HAProxyMessage relayed = getRelayedHaProxyMessage();

    assertThat(relayed).as("PROXY protocol message should be decoded even with TLS").isNotNull();
    assertThat(relayed.sourceAddress()).isEqualTo(SOURCE_ADDRESS);
    assertThat(relayed.destinationAddress()).isEqualTo(DESTINATION_ADDRESS);
  }
}
