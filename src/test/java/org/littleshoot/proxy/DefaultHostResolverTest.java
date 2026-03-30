package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

final class DefaultHostResolverTest {
  private final DefaultHostResolver resolver = new DefaultHostResolver();

  @Test
  void resolveLocalhost() throws UnknownHostException {
    InetSocketAddress address = resolver.resolve("localhost", 8080);

    assertThat(address).isNotNull();
    assertThat(address.getPort()).isEqualTo(8080);
    assertThat(address.getAddress()).isNotNull();
    assertThat(address.getAddress().isLoopbackAddress())
        .as("localhost should resolve to 127.0.0.1")
        .isTrue();
  }

  @Test
  void resolveWithIpAddress() throws UnknownHostException {
    InetSocketAddress address = resolver.resolve("127.0.0.1", 9090);

    assertThat(address).isNotNull();
    assertThat(address.getPort()).isEqualTo(9090);
    assertThat(address.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
  }

  @Test
  void resolveUnknownHost() {
    assertThatThrownBy(() -> resolver.resolve("this-host.invalid", 80))
        .isInstanceOf(UnknownHostException.class);
  }

  @Test
  void resolveReturnsCorrectPort() throws UnknownHostException {
    InetSocketAddress address = resolver.resolve("localhost", 443);

    assertThat(address.getPort()).isEqualTo(443);
  }
}
