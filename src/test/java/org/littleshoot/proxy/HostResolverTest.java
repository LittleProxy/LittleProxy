package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/** Tests for HostResolver interface. */
class HostResolverTest {

  @Test
  void testInterfaceDefinition() {
    assertThat(HostResolver.class).isInterface();
  }

  @Test
  void testHasResolveMethod() throws NoSuchMethodException {
    assertThat(HostResolver.class.getMethod("resolve", String.class, int.class)).isNotNull();
    assertThat(HostResolver.class.getMethod("resolve", String.class, int.class).getReturnType())
        .isEqualTo(InetSocketAddress.class);
  }

  @Test
  void testResolveMethodThrowsUnknownHostException() throws NoSuchMethodException {
    assertThat(HostResolver.class.getMethod("resolve", String.class, int.class).getExceptionTypes())
        .contains(UnknownHostException.class);
  }

  @Test
  void testSimpleImplementation() throws UnknownHostException {
    HostResolver resolver =
            (host, port) -> new InetSocketAddress("127.0.0.1", port);

    InetSocketAddress address = resolver.resolve("example.com", 8080);

    assertThat(address).isNotNull();
    assertThat(address.getPort()).isEqualTo(8080);
  }
}
