package org.littleshoot.proxy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests just a single basic proxy.
 */
@Tag("slow-test")
public final class IdlingProxyTest extends AbstractProxyTest {
    @Override
    protected void setUp() {
        proxyServer = bootstrapProxy()
                .withPort(0)
                .withIdleConnectionTimeout(1)
                .start();
    }

    @Test
    public void testTimeout() {
        ResponseInfo response = httpGetWithApacheClient(webHost, "/hang", true, false);
        assertThat(response.getStatusCode()).as("Received: %s", response).isEqualTo(504);
    }

}
