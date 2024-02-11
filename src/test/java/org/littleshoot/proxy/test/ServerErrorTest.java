package org.littleshoot.proxy.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.test.HttpClientUtil.performLocalHttpGet;

public final class ServerErrorTest {
    private HttpProxyServer proxyServer;

    @Test
    public void testInvalidServerResponse() throws IOException {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        // we have to create our own socket here, since any proper http server (jetty, mockserver, etc.) won't allow us to
        // send invalid responses.
        try (ServerSocket socket = createServerWithBadResponse()) {
            org.apache.http.HttpResponse response = performLocalHttpGet(socket.getLocalPort(), "/", proxyServer);

            assertThat(response.getStatusLine().getStatusCode())
              .as("Expected to receive a 502 Bad Gateway after server responded with invalid response")
              .isEqualTo(502);
        }
    }

    @AfterEach
    void tearDown() {
        if (proxyServer != null) {
            proxyServer.abort();
        }
    }

    /**
     * Creates a ServerSocket that will read an HTTP request and response with an invalid response.
     * NOTE: the ServerSocket must be closed after the response is consumed.
     */
    private static ServerSocket createServerWithBadResponse() {
        final ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Runnable server = () -> {
            try {
                Socket socket = serverSocket.accept();

                try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                    while (!in.readLine().isEmpty()) {
                        // read the request up to the double-CRLF
                    }

                    // write a response with an invalid HTTP version
                    out.write("HTTP/1.12312312312312411231231231 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Content-Length: 0\r\n" +
                            "\r\n");
                    out.flush();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        // start the server in a separate thread
        Thread serverThread = new Thread(server);
        serverThread.setDaemon(true);
        serverThread.start();

        // wait for the server to start
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        return serverSocket;
    }

}
