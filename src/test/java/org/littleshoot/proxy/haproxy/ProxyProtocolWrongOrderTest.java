package org.littleshoot.proxy.haproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Negative test: when the PROXY protocol header is sent after  the TLS handshake (i.e. encrypted inside the tunnel), the proxy must NOT successfully decode it.
 */
public final class ProxyProtocolWrongOrderTest extends BaseProxyProtocolTest {

  @Override
  protected boolean useTlsInbound() {
    return true;
  }

  @Override
  protected boolean sendProxyHeaderBeforeTls() {
    return false;
  }

  @Test
  void proxyProtocolInsideTlsTunnelIsNotDecoded() throws Exception {
    setup(true, true);

    boolean tlsCompleted = clientTlsHandshakeDone.await(5, TimeUnit.SECONDS);

    if (tlsCompleted && isClientTlsHandshakeSuccess()) {
      assertThat(getRelayedHaProxyMessage())
          .as("PROXY header sent inside TLS must not be decoded")
          .isNull();
    } else {
      assertThat(isClientTlsHandshakeSuccess())
          .as(
              "TLS should fail when PROXY header is not sent before TLS. " + "Cause: %s",
              getClientTlsHandshakeFailureCause())
          .isFalse();
    }
  }
}
