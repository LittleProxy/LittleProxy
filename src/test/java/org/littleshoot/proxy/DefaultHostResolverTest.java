package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class DefaultHostResolverTest {

  @Test
  void testImplementsHostResolver() {
    assertThat(new DefaultHostResolver()).isInstanceOf(HostResolver.class);
  }

  @Test
  void testResolveLocalhost() throws UnknownHostException {
    DefaultHostResolver resolver = new DefaultHostResolver();

    InetSocketAddress address = resolver.resolve("localhost", 8080);

    assertThat(address).isNotNull();
    assertThat(address.getPort()).isEqualTo(8080);
    assertThat(address.getAddress()).isNotNull();
    // localhost should resolve to 127.0.0.1
    assertThat(address.getAddress().isLoopbackAddress()).isTrue();
  }

  @Test
  void testResolveWithIpAddress() throws UnknownHostException {
    DefaultHostResolver resolver = new DefaultHostResolver();

    InetSocketAddress address = resolver.resolve("127.0.0.1", 9090);

    assertThat(address).isNotNull();
    assertThat(address.getPort()).isEqualTo(9090);
    assertThat(address.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
  }

  @Test
  void testResolveUnknownHost() {
    DefaultHostResolver resolver = new DefaultHostResolver();

    assertThatThrownBy(() -> resolver.resolve("this-host-does-not-exist-12345.xyz", 80))
        .isInstanceOf(UnknownHostException.class);
  }

  @Test
  void testResolveReturnsCorrectPort() throws UnknownHostException {
    DefaultHostResolver resolver = new DefaultHostResolver();

    InetSocketAddress address = resolver.resolve("localhost", 443);

    assertThat(address.getPort()).isEqualTo(443);
  }
}
