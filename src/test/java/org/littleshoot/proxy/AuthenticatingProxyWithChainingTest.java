package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpRequest;
import java.util.Queue;
import org.littleshoot.proxy.impl.ClientDetails;

/** Tests a single proxy that requires username/password authentication. */
public class AuthenticatingProxyWithChainingTest extends BaseProxyTest
    implements ProxyAuthenticator, ChainedProxyManager {

  private ClientDetails savedClientDetails;

  @Override
  protected void setUp() {
    proxyServer =
        bootstrapProxy()
            .withPort(0)
            .withProxyAuthenticator(this)
            .withChainProxyManager(this)
            .start();
  }

  @Override
  protected String getUsername() {
    return "user1";
  }

  @Override
  protected String getPassword() {
    return "user2";
  }

  @Override
  public boolean authenticate(String userName, String password) {
    return getUsername().equals(userName) && getPassword().equals(password);
  }

  @Override
  protected boolean isAuthenticating() {
    return true;
  }

  @Override
  public String getRealm() {
    return null;
  }

  @Override
  public void lookupChainedProxies(
      HttpRequest httpRequest, Queue<ChainedProxy> chainedProxies, ClientDetails clientDetails) {
    savedClientDetails = clientDetails;
    chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    assertThat(savedClientDetails.getUserName()).isEqualTo(getUsername());
    assertThat(savedClientDetails.getClientAddress().getAddress().isLoopbackAddress()).isTrue();
  }
}
