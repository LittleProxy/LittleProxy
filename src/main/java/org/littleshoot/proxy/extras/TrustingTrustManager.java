package org.littleshoot.proxy.extras;

import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/** TrustManager that trusts all servers */
class TrustingTrustManager implements X509TrustManager {
  @Override
  public void checkClientTrusted(X509Certificate[] arg0, String arg1) {}

  @Override
  public void checkServerTrusted(X509Certificate[] arg0, String arg1) {}

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return null;
  }
}
