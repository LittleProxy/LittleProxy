package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.reflect.Field;
import java.net.SocketException;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ChainedProxy;

class ProxyToServerConnectionCleanupTest {
  @Test
  void successfulConnectThenResetDoesNotReleaseAggregatedRequestTwice() throws Exception {
    ProxyToServerConnection connection = mock(ProxyToServerConnection.class, CALLS_REAL_METHODS);
    ClientToProxyConnection client = mock(ClientToProxyConnection.class);
    FullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, "example.com:443");
    setField(ProxyConnection.class, connection, "LOG", new ProxyConnectionLogger(connection));
    setField(ProxyToServerConnection.class, connection, "clientConnection", client);
    setField(
        ProxyToServerConnection.class,
        connection,
        "availableChainedProxies",
        new ConcurrentLinkedQueue<ChainedProxy>());
    setField(ProxyToServerConnection.class, connection, "initialRequest", request);

    connection.connectionSucceeded(false);
    assertThat(request.refCnt()).isZero();
    verify(client).serverConnectionSucceeded(connection, false);

    // A later reset must not release the request that was already dropped after CONNECT.
    assertThat(connection.connectionFailed(new SocketException("Connection reset"))).isFalse();
    assertThat(connection.getInitialRequest()).isNull();
    assertThat(request.refCnt()).isZero();
  }

  private static void setField(
      Class<?> owner, ProxyToServerConnection connection, String name, Object value)
      throws ReflectiveOperationException {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(connection, value);
  }
}
