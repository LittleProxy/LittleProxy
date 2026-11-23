package org.littleshoot.proxy;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * A chained proxy that accepts TCP connections but never answers the HTTP CONNECT must not leave
 * the client connection open forever (CLOSE_WAIT leak).
 *
 * @see <a href="https://github.com/LittleProxy/LittleProxy/issues/6">#6</a>
 */
@Tag("slow-test")
public final class UnresponsiveChainedProxyTest {
  private static final int IDLE_TIMEOUT_SECONDS = 3;
  private static final int CLIENT_READ_TIMEOUT_MILLIS = 15_000;

  /** Sockets accepted by the fake chained proxy; held open and never answered. */
  private final List<Socket> acceptedByChainedProxy = new CopyOnWriteArrayList<>();

  private ServerSocket unresponsiveChainedProxy;
  private HttpProxyServer proxyServer;

  @BeforeEach
  void setUp() throws IOException {
    unresponsiveChainedProxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread acceptor =
        new Thread(
            () -> {
              while (!unresponsiveChainedProxy.isClosed()) {
                try {
                  acceptedByChainedProxy.add(unresponsiveChainedProxy.accept());
                } catch (IOException e) {
                  return;
                }
              }
            },
            "unresponsive-chained-proxy");
    acceptor.setDaemon(true);
    acceptor.start();

    InetSocketAddress chainedProxyAddress =
        new InetSocketAddress(
            InetAddress.getLoopbackAddress(), unresponsiveChainedProxy.getLocalPort());
    proxyServer =
        DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withIdleConnectionTimeout(IDLE_TIMEOUT_SECONDS)
            .withChainProxyManager(
                (httpRequest, chainedProxies, clientDetails) ->
                    chainedProxies.add(
                        new ChainedProxyAdapter() {
                          @Override
                          public InetSocketAddress getChainedProxyAddress() {
                            return chainedProxyAddress;
                          }
                        }))
            .start();
  }

  @AfterEach
  void tearDown() throws IOException {
    proxyServer.abort();
    unresponsiveChainedProxy.close();
    for (Socket socket : acceptedByChainedProxy) {
      socket.close();
    }
  }

  @Test
  void respondsWithErrorAndReadsClientCloseAfterChainedProxyTimesOut() throws IOException {
    try (Socket client = connectToProxyAndSendConnect()) {
      assertThat(readStatusLine(client)).startsWith("HTTP/1.1 502");

      // The client hangs up (as curl does). The proxy must notice and close its side, otherwise the
      // socket stays in CLOSE_WAIT forever.
      client.shutdownOutput();
      assertThat(readUntilEof(client)).as("proxy should close the client connection").isTrue();
    }
  }

  @Test
  void closesIdleClientAfterChainedProxyTimesOut() throws IOException {
    try (Socket client = connectToProxyAndSendConnect()) {
      assertThat(readStatusLine(client)).startsWith("HTTP/1.1 502");

      // The client stays connected but idle: the proxy's idle timeout must close it.
      assertThat(readUntilEof(client)).as("proxy should close the idle client connection").isTrue();
    }
  }

  private Socket connectToProxyAndSendConnect() throws IOException {
    Socket client =
        new Socket(InetAddress.getLoopbackAddress(), proxyServer.getListenAddress().getPort());
    client.setSoTimeout(CLIENT_READ_TIMEOUT_MILLIS);
    OutputStream out = client.getOutputStream();
    out.write(
        "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".getBytes(US_ASCII));
    out.flush();
    return client;
  }

  /**
   * Reads the status line byte by byte from the raw stream, so that nothing beyond it is consumed
   * into a buffer that would be discarded.
   */
  private static String readStatusLine(Socket client) throws IOException {
    InputStream in = client.getInputStream();
    StringBuilder statusLine = new StringBuilder();
    int b;
    while ((b = in.read()) != -1 && b != '\n') {
      if (b != '\r') {
        statusLine.append((char) b);
      }
    }
    assertThat(statusLine).as("status line").isNotEmpty();
    return statusLine.toString();
  }

  /** Returns true if the proxy closed the connection before the client's read timeout. */
  private static boolean readUntilEof(Socket client) {
    byte[] buffer = new byte[1024];
    try {
      while (client.getInputStream().read(buffer) != -1) {
        // drain the rest of the error response
      }
      return true;
    } catch (IOException e) {
      // SocketTimeoutException: proxy never closed the connection
      return false;
    }
  }
}
