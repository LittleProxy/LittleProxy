package org.littleshoot.proxy.haproxy;

import static java.lang.String.valueOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.haproxy.HAProxyMessage;
import org.junit.jupiter.api.Test;

public final class ProxyProtocolTest extends BaseProxyProtocolTest {

  private static final String LOCALHOST = "127.0.0.1";
  private static final boolean ACCEPT_PROXY = true;
  private static final boolean SEND_PROXY = true;
  private static final boolean DO_NOT_ACCEPT_PROXY = false;
  private static final boolean DO_NOT_SEND_PROXY = false;

  @Test
  public void canRelayProxyProtocolHeader() throws Exception {
    setup(ACCEPT_PROXY, SEND_PROXY);
    HAProxyMessage haProxyMessage = getRelayedHaProxyMessage();
    assertThat(haProxyMessage).isNotNull();
    assertThat(haProxyMessage.sourceAddress()).isEqualTo(SOURCE_ADDRESS);
    assertThat(haProxyMessage.destinationAddress()).isEqualTo(DESTINATION_ADDRESS);
    assertThat(valueOf(haProxyMessage.sourcePort())).isEqualTo(SOURCE_PORT);
    assertThat(valueOf(haProxyMessage.destinationPort())).isEqualTo(DESTINATION_PORT);
  }

  @Test
  public void canSendProxyProtocolHeader() throws Exception {
    setup(DO_NOT_ACCEPT_PROXY, SEND_PROXY);
    HAProxyMessage haProxyMessage = getRelayedHaProxyMessage();
    assertThat(haProxyMessage).isNotNull();
    assertThat(haProxyMessage.sourceAddress()).isEqualTo(LOCALHOST);
    assertThat(haProxyMessage.destinationAddress()).isEqualTo(LOCALHOST);
    assertThat(valueOf(haProxyMessage.destinationPort())).isEqualTo(valueOf(serverPort));
  }

  @Test
  public void canAcceptProxyProtocolHeader() throws Exception {
    setup(ACCEPT_PROXY, DO_NOT_SEND_PROXY);
    HAProxyMessage haProxyMessage = getRelayedHaProxyMessage();
    assertThat(haProxyMessage).isNull();
  }
}
