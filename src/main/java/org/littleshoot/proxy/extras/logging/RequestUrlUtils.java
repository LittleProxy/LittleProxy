package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import org.littleshoot.proxy.FlowContext;

/** Utilities for reconstructing request URLs for logging. */
public final class RequestUrlUtils {

  private RequestUrlUtils() {}

  public static String getFullUrl(FlowContext flowContext, HttpRequest request) {
    String uri = request.uri();

    if (uri.startsWith("http://") || uri.startsWith("https://")) {
      return uri;
    }

    if (request.method().name().equals("CONNECT")) {
      return uri;
    }

    String host = request.headers().get("Host");
    if (host == null || host.isEmpty()) {
      return uri;
    }

    String scheme = flowContext.getClientSslSession() != null ? "https" : "http";

    if (uri.startsWith("/")) {
      return scheme + "://" + host + uri;
    }
    return scheme + "://" + host + "/" + uri;
  }
}
