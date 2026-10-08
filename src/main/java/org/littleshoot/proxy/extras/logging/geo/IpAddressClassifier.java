package org.littleshoot.proxy.extras.logging.geo;

import java.net.Inet6Address;
import java.net.InetAddress;

final class IpAddressClassifier {

  private IpAddressClassifier() {}

  static boolean isLocalOrPrivate(InetAddress address) {
    if (address == null) {
      return false;
    }

    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()) {
      return true;
    }

    if (address instanceof Inet6Address) {
      byte[] bytes = address.getAddress();
      if (bytes.length > 0) {
        int firstByte = bytes[0] & 0xFF;
        return firstByte == 0xFC || firstByte == 0xFD;
      }
    }

    return false;
  }
}
