package org.littleshoot.proxy;

import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.jspecify.annotations.Nullable;

/**
 * Jetty handler that fully reads the request body before responding with 200 OK.
 *
 * <p>Reading the whole body matters for tests that send a request body through the proxy: a server
 * that responds without consuming the body (e.g. a bare Jetty {@code Server} returning 404) may
 * close the connection while the proxy is still forwarding the last chunks, which makes callbacks
 * like {@link HttpFilters#proxyToServerRequestSent()} flaky.
 *
 * <p>If the request URI contains "hang", the handler sleeps for 90 seconds before responding.
 */
final class ReadRequestBodyHandler extends AbstractHandler {
  private final byte @Nullable [] content;

  /**
   * @param content the response body; if null, the body is "Received N bytes\n", where N is the
   *     number of request body bytes read
   */
  ReadRequestBodyHandler(byte @Nullable [] content) {
    this.content = content;
  }

  @Override
  public void handle(
      String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (request.getRequestURI().contains("hang")) {
      System.out.println("Hanging as requested");
      try {
        Thread.sleep(90000);
      } catch (InterruptedException ie) {
        System.out.println("Stopped hanging due to interruption");
      }
    }

    long numberOfBytesRead = 0;
    try (InputStream in = new BufferedInputStream(request.getInputStream())) {
      while (in.read() != -1) {
        numberOfBytesRead += 1;
      }
    }
    System.out.println("Done reading # of bytes: " + numberOfBytesRead);

    byte[] body =
        content != null ? content : ("Received " + numberOfBytesRead + " bytes\n").getBytes(UTF_8);
    response.setStatus(HttpServletResponse.SC_OK);
    baseRequest.setHandled(true);
    response.addHeader("Content-Length", Integer.toString(body.length));
    response.getOutputStream().write(body);
  }
}
