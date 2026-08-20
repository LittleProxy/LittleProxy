package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DefaultHttpProxyServerBootstrapTest {

  private static Object getField(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  @Test
  void constructorParsesPoolSharedMitmConnectionsFromProperties() throws Exception {
    Properties props = new Properties();
    props.setProperty("pool_shared_mitm_connections", "true");
    DefaultHttpProxyServerBootstrap bootstrap = new DefaultHttpProxyServerBootstrap(props);
    assertThat(getField(bootstrap, "poolSharedMitmConnections")).isEqualTo(true);
  }

  @Test
  void constructorDefaultsPoolSharedMitmConnectionsToFalse() throws Exception {
    Properties props = new Properties();
    DefaultHttpProxyServerBootstrap bootstrap = new DefaultHttpProxyServerBootstrap(props);
    assertThat(getField(bootstrap, "poolSharedMitmConnections")).isEqualTo(false);
  }

  @Test
  void constructorParsesPoolPerRequestInMitmFromProperties() throws Exception {
    Properties props = new Properties();
    props.setProperty("pool_per_request_in_mitm", "true");
    DefaultHttpProxyServerBootstrap bootstrap = new DefaultHttpProxyServerBootstrap(props);
    assertThat(getField(bootstrap, "poolPerRequestInMitm")).isEqualTo(true);
  }

  @Test
  void constructorDefaultsPoolPerRequestInMitmToFalse() throws Exception {
    Properties props = new Properties();
    DefaultHttpProxyServerBootstrap bootstrap = new DefaultHttpProxyServerBootstrap(props);
    assertThat(getField(bootstrap, "poolPerRequestInMitm")).isEqualTo(false);
  }

  @Test
  void constructorParsesBothMitmPoolFlags() throws Exception {
    Properties props = new Properties();
    props.setProperty("pool_shared_mitm_connections", "true");
    props.setProperty("pool_per_request_in_mitm", "true");
    DefaultHttpProxyServerBootstrap bootstrap = new DefaultHttpProxyServerBootstrap(props);
    assertThat(getField(bootstrap, "poolSharedMitmConnections")).isEqualTo(true);
    assertThat(getField(bootstrap, "poolPerRequestInMitm")).isEqualTo(true);
  }
}
