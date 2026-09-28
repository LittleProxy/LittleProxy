package org.littleshoot.proxy.extras.logging.geo;

import java.net.InetAddress;

public final class LocalGeoCountryResolver implements GeoCountryResolver {

  @Override
  public String resolveCountryCode(InetAddress ip) {
    return IpAddressClassifier.isLocalOrPrivate(ip) ? LOCAL : UNKNOWN;
  }
}
