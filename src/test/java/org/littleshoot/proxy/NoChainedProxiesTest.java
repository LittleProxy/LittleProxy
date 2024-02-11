package org.littleshoot.proxy;

import org.junit.jupiter.api.Test;

/**
 * Tests that when there are no chained proxies, we get a bad gateway.
 */
public final class NoChainedProxiesTest extends AbstractProxyTest {
    @Override
    protected void setUp() {
        proxyServer = bootstrapProxy()
                .withPort(0)
                .withChainProxyManager((httpRequest, chainedProxies, clientDetails) -> {
                    // Leave list empty
                })
                .withIdleConnectionTimeout(1)
                .start();
    }

    @Test
    public void testNoChainedProxy() {
        ResponseInfo response = httpGetWithApacheClient(webHost,
                DEFAULT_RESOURCE, true, false);
        assertReceivedBadGateway(response);
    }
}
