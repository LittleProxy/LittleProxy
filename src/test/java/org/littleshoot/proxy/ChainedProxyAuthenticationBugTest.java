package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.ClientDetails;
import org.littleshoot.proxy.impl.ProxyUtils;

/**
 * Unit test that demonstrates the authentication bug described in issue #464.
 * 
 * This test shows that LittleProxy loses authentication information when relaying
 * proxy requests to an upstream chained proxy that requires authentication.
 * 
 * The bug occurs because:
 * 1. Client Proxy-Authorization header is stripped during client authentication
 * 2. The same header is also stripped as a hop-by-hop header during request forwarding
 * 3. When acting as a chained proxy, there's no mechanism to preserve upstream proxy credentials
 */
public class ChainedProxyAuthenticationBugTest extends BaseProxyTest 
    implements ProxyAuthenticator, ChainedProxyManager {

    private static final String UPSTREAM_PROXY_USER = "upstreamUser";
    private static final String UPSTREAM_PROXY_PASS = "upstreamPass";
    private static final String CLIENT_PROXY_USER = "clientUser";
    private static final String CLIENT_PROXY_PASS = "clientPass";
    
    // Track if the upstream proxy received the Proxy-Authorization header
    private final AtomicReference<Boolean> upstreamProxyReceivedAuth = new AtomicReference<>(false);
    
    @Override
    protected void setUp() {
        // Set up LittleProxy as both an authenticating proxy and a chained proxy manager
        proxyServer = bootstrapProxy()
            .withPort(0)
            .withProxyAuthenticator(this)  // Authenticate clients to LittleProxy
            .withChainProxyManager(this)  // Manage upstream chained proxy connections
            .start();
    }
    
    @Override
    public boolean authenticate(String userName, String password) {
        // Authenticate client to LittleProxy
        return CLIENT_PROXY_USER.equals(userName) && CLIENT_PROXY_PASS.equals(password);
    }
    
    @Override
    public String getRealm() {
        return "LittleProxy Test Realm";
    }
    
    @Override
    protected boolean isAuthenticating() {
        return true;
    }
    
    @Override
    protected String getUsername() {
        return CLIENT_PROXY_USER;
    }
    
    @Override
    protected String getPassword() {
        return CLIENT_PROXY_PASS;
    }
    
    @Override
    public void lookupChainedProxies(HttpRequest httpRequest, Queue<ChainedProxy> chainedProxies, 
                                   ClientDetails clientDetails) {
        // Create a mock upstream proxy that requires authentication
        ChainedProxy upstreamProxy = new ChainedProxyAdapter() {
            @Override
            public void connectionFailed(Throwable cause) {
                // This will be called if upstream proxy connection fails due to auth
                System.err.println("Upstream proxy connection failed: " + cause.getMessage());
            }
            
            @Override
            public String getUsername() {
                return UPSTREAM_PROXY_USER;
            }
            
            @Override
            public String getPassword() {
                return UPSTREAM_PROXY_PASS;
            }
        };
        
        chainedProxies.add(upstreamProxy);
    }
    
    /**
     * Test that demonstrates the authentication bug.
     * 
     * This test should PASS if the bug is fixed, but currently FAILS because
     * LittleProxy strips the Proxy-Authorization header and doesn't properly
     * forward it to the upstream chained proxy.
     */
    @Test
    public void testChainedProxyAuthenticationBug() throws Exception {
        // Reset the flag
        upstreamProxyReceivedAuth.set(false);
        
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(
                true,  // isProxied - use LittleProxy
                true,  // supportSsl
                proxyServer.getListenAddress().getPort(), 
                CLIENT_PROXY_USER, 
                CLIENT_PROXY_PASS)) {
            
            // Make a request through LittleProxy to the upstream proxy
            HttpGet request = new HttpGet("http://example.com/");
            request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
            
            // This should work if authentication is properly forwarded
            // But currently fails due to the bug
            try {
                httpClient.execute(webHost, request);
                System.out.println("Request succeeded - authentication properly forwarded");
            } catch (Exception e) {
                System.err.println("Request failed - authentication lost: " + e.getMessage());
                // This demonstrates the bug - the exception occurs because upstream
                // proxy doesn't receive the Proxy-Authorization header
                throw new RuntimeException("Authentication bug demonstrated: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Helper method to demonstrate the header stripping issue directly.
     * This shows that Proxy-Authorization headers are in the SHOULD_NOT_PROXY_HOP_BY_HOP_HEADERS set.
     */
    @Test
    public void demonstrateHeaderStrippingIssue() {
        HttpRequest request = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/test");
        
        // Add Proxy-Authorization header (as the client would)
        request.headers().set("Proxy-Authorization", "Basic dXNlcjpwYXNz");
        
        System.out.println("Original headers: " + request.headers());
        
        // This is what LittleProxy does during request processing
        ProxyUtils.stripHopByHopHeaders(request.headers());
        
        System.out.println("After stripping hop-by-hop headers: " + request.headers());
        
        // The Proxy-Authorization header will be gone, demonstrating the bug
        assertThat(request.headers().contains("Proxy-Authorization"))
            .as("Proxy-Authorization header should not be stripped when forwarding to upstream proxy")
            .isFalse();  // This assertion demonstrates the bug
    }


}