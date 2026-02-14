package org.littleshoot.proxy.impl;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.stream.Stream;

import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class Hostname {
  private static final Logger LOG = LoggerFactory.getLogger(Hostname.class);

  private static volatile String hostname;

  @Nullable
  static String getHostName() {
    if (hostname == null) {
      hostname = resolveHostName();
    }
    return hostname;
  }

  @Nullable
  private static String resolveHostName() {
    long startTime = nanoTime();
    String hostName = byAllMeans(
      env("HOSTNAME"), // Most OSs
      env("COMPUTERNAME"), // Windows
      Hostname::executeHostname,
      Hostname::getLocalHost
    );
    long duration = NANOSECONDS.toMillis(nanoTime() - startTime);
    LOG.info("Resolved local machine's hostname \"{}\" in {} ms.", hostName, duration);
    return hostName;
  }

  @Nullable
  @SafeVarargs
  private static String byAllMeans(SupplierEx<String>... means) {
    return Stream.of(means)
      .map(mean -> getOrNull(mean))
      .filter(host -> host != null)
      .findFirst()
      .orElse(null);
  }

  @Nullable
  private static String getOrNull(SupplierEx<String> s) {
    try {
      return s.get();
    }
    catch (Exception e) {
      LOG.info("Failed to resolve local machine's hostname", e);
      return null;
    }
  }

  private static SupplierEx<String> env(String name) {
    return () -> System.getenv(name);
  }

  /**
   * "hostname" command works on Windows, Mac, and Linux.
   * Usually much faster than {@link InetAddress#getLocalHost()}.
   */
  private static String executeHostname() throws IOException, InterruptedException {
    Process p = new ProcessBuilder("hostname").start();

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      String line = reader.readLine();
      if (p.waitFor(5, SECONDS) && line != null) {
        return line.trim();
      }
    }
    return null;
  }

  private static String getLocalHost() throws UnknownHostException {
    return InetAddress.getLocalHost().getHostName();
  }
}
