package org.littleshoot.proxy.extras.logging.geo;

import java.net.InetAddress;

public interface GeoCountryResolver {

  String LOCAL = "LOCAL";

  String UNKNOWN = "UNKNOWN";

  String resolveCountryCode(InetAddress ip);
}
