package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpRequest;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/**
 * Tests for MitmManager interface. Since MitmManager is an interface, these tests document the
 * expected behavior and test a simple implementation.
 */
class MitmManagerTest {

  @Test
  void testInterfaceDefinition() {
    // Verify MitmManager is an interface
    assertThat(MitmManager.class).isInterface();
  }

  @Test
  void testHasServerSslEngineWithPeerInfoMethod() throws NoSuchMethodException {
    // Verify the interface has the serverSslEngine(String, int) method
    assertThat(MitmManager.class.getMethod("serverSslEngine", String.class, int.class)).isNotNull();
  }

  @Test
  void testHasServerSslEngineMethod() throws NoSuchMethodException {
    // Verify the interface has the serverSslEngine() method
    assertThat(MitmManager.class.getMethod("serverSslEngine")).isNotNull();
  }

  @Test
  void testHasClientSslEngineForMethod() throws NoSuchMethodException {
    // Verify the interface has the clientSslEngineFor(HttpRequest, SSLSession) method
    assertThat(
            MitmManager.class.getMethod("clientSslEngineFor", HttpRequest.class, SSLSession.class))
        .isNotNull();
  }

  @Test
  void testSimpleImplementation() throws Exception {
    // Create a simple test implementation
    MitmManager testManager =
        new MitmManager() {
          @Override
          public SSLEngine serverSslEngine(String peerHost, int peerPort) {
            try {
              SSLContext context = SSLContext.getDefault();
              return context.createSSLEngine(peerHost, peerPort);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public SSLEngine serverSslEngine() {
            try {
              SSLContext context = SSLContext.getDefault();
              return context.createSSLEngine();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public SSLEngine clientSslEngineFor(
              HttpRequest httpRequest, SSLSession serverSslSession) {
            try {
              SSLContext context = SSLContext.getDefault();
              return context.createSSLEngine();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        };

    // Verify the implementation works
    assertThat(testManager).isNotNull();

    SSLEngine engine1 = testManager.serverSslEngine();
    assertThat(engine1).isNotNull();

    SSLEngine engine2 = testManager.serverSslEngine("example.com", 443);
    assertThat(engine2).isNotNull();
    assertThat(engine2.getPeerHost()).isEqualTo("example.com");
    assertThat(engine2.getPeerPort()).isEqualTo(443);
  }

  @Test
  void testReturnTypes() throws NoSuchMethodException {
    // Verify all methods return SSLEngine
    assertThat(
            MitmManager.class.getMethod("serverSslEngine", String.class, int.class).getReturnType())
        .isEqualTo(SSLEngine.class);
    assertThat(MitmManager.class.getMethod("serverSslEngine").getReturnType())
        .isEqualTo(SSLEngine.class);
    assertThat(
            MitmManager.class
                .getMethod("clientSslEngineFor", HttpRequest.class, SSLSession.class)
                .getReturnType())
        .isEqualTo(SSLEngine.class);
  }
}
