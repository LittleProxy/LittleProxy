package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import io.netty.handler.codec.http.HttpObject;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;

class ChainedProxyAdapterTest {

  @Test
  void testImplementsChainedProxy() {
    assertThat(new ChainedProxyAdapter()).isInstanceOf(ChainedProxy.class);
  }

  @Test
  void testFallbackToDirectConnectionConstant() {
    assertThat(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION).isNotNull();
    assertThat(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION).isInstanceOf(ChainedProxy.class);
  }

  @Test
  void testGetChainedProxyAddress() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    InetSocketAddress address = adapter.getChainedProxyAddress();

    assertThat(address).isNull();
  }

  @Test
  void testGetLocalAddress() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    InetSocketAddress address = adapter.getLocalAddress();

    assertThat(address).isNull();
  }

  @Test
  void testGetTransportProtocol() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    TransportProtocol protocol = adapter.getTransportProtocol();

    assertThat(protocol).isEqualTo(TransportProtocol.TCP);
  }

  @Test
  void testGetChainedProxyType() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    ChainedProxyType type = adapter.getChainedProxyType();

    assertThat(type).isEqualTo(ChainedProxyType.HTTP);
  }

  @Test
  void testGetUsername() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    String username = adapter.getUsername();

    assertThat(username).isNull();
  }

  @Test
  void testGetPassword() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    String password = adapter.getPassword();

    assertThat(password).isNull();
  }

  @Test
  void testRequiresEncryption() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    boolean requiresEncryption = adapter.requiresEncryption();

    assertThat(requiresEncryption).isFalse();
  }

  @Test
  void testNewSslEngine() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    SSLEngine engine = adapter.newSslEngine();

    assertThat(engine).isNull();
  }

  @Test
  void testNewSslEngineWithPeerInfo() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    SSLEngine engine = adapter.newSslEngine("example.com", 443);

    assertThat(engine).isNull();
  }

  @Test
  void testFilterRequest() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();
    HttpObject httpObject = mock(HttpObject.class);

    assertThatCode(() -> adapter.filterRequest(httpObject)).doesNotThrowAnyException();
  }

  @Test
  void testConnectionSucceeded() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    assertThatCode(adapter::connectionSucceeded).doesNotThrowAnyException();
  }

  @Test
  void testConnectionFailed() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();
    Throwable cause = new RuntimeException("Test error");

    assertThatCode(() -> adapter.connectionFailed(cause)).doesNotThrowAnyException();
  }

  @Test
  void testConnectionFailedWithNull() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    assertThatCode(() -> adapter.connectionFailed(null)).doesNotThrowAnyException();
  }

  @Test
  void testDisconnected() {
    ChainedProxyAdapter adapter = new ChainedProxyAdapter();

    assertThatCode(adapter::disconnected).doesNotThrowAnyException();
  }
}
