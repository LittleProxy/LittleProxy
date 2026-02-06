package org.littleshoot.proxy.extras;

public class TestMitmManager extends SelfSignedMitmManager {
  public TestMitmManager() {
    super("target/littleproxy_keystore.jks", true, true);
  }
}
