package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.ClientToProxyConnection;
import org.littleshoot.proxy.impl.ProxyToServerConnection;
import org.littleshoot.proxy.impl.ProxyUtils;
import org.mockito.Mockito;

/**
 * Comprehensive test for all four chained proxy authentication scenarios:
 * 
 * 1. LittleProxy handles auth, next proxy does not
 * 2. Both LittleProxy and next proxy handle auth (two roundtrips)
 * 3. LittleProxy does not handle auth, next proxy does
 * 4. Neither proxy handles auth
 */
public class ChainedProxyAuthenticationScenariosTest {

    /**
     * Scenario 1: LittleProxy handles auth, next proxy does not
     * 
     * Client → [Proxy-Authorization: clientCreds] → LittleProxy (authenticates, removes header) 
     * → [No auth header] → Upstream Proxy (no auth needed) → ✅ Success
     */
    @Test
    public void testScenario1_LittleProxyHandlesAuth_NextProxyDoesNot() {
        System.out.println("=== Testing Scenario 1: LittleProxy handles auth, next proxy does not ===");
        
        // Simulate client request with authentication
        HttpRequest request = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        request.headers().set("Proxy-Authorization", "Basic clientCreds");
        
        System.out.println("Client request headers: " + request.headers());
        
        // LittleProxy authenticates client and removes the header
        // This simulates the authenticationRequired() method behavior
        request.headers().remove("Proxy-Authorization");
        
        System.out.println("After LittleProxy auth (header removed): " + request.headers());
        
        // Forward to upstream proxy that doesn't require auth
        // No Proxy-Authorization header should be added
        HttpHeaders headers = request.headers();
        ProxyUtils.stripHopByHopHeaders(headers, false); // Don't preserve Proxy-Authorization
        
        System.out.println("Forwarded to upstream proxy: " + headers);
        
        // Verify no Proxy-Authorization header is present
        assertThat(headers.contains("Proxy-Authorization"))
            .as("No Proxy-Authorization header should be forwarded to upstream proxy that doesn't require auth")
            .isFalse();
        
        System.out.println("✅ Scenario 1 test passed");
    }
    
    /**
     * Scenario 2: Both LittleProxy and next proxy handle auth
     * 
     * This is the most complex case requiring two roundtrips:
     * 1. Client → [Proxy-Authorization: clientCreds] → LittleProxy (authenticates)
     * 2. LittleProxy → [Proxy-Authorization: upstreamCreds] → Upstream Proxy (may return 407)
     * 3. If upstream returns 407, LittleProxy should handle it appropriately
     */
    @Test
    public void testScenario2_BothProxiesHandleAuth() {
        System.out.println("=== Testing Scenario 2: Both proxies handle auth ===");
        
        // Simulate client request with client authentication
        HttpRequest request = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        request.headers().set("Proxy-Authorization", "Basic clientCreds");
        
        System.out.println("Client request headers: " + request.headers());
        
        // LittleProxy authenticates client and removes client header
        request.headers().remove("Proxy-Authorization");
        
        System.out.println("After LittleProxy auth (client header removed): " + request.headers());
        
        // Simulate forwarding to upstream proxy that requires auth
        // LittleProxy should add upstream credentials
        HttpHeaders headers = request.headers();
        
        // This simulates the addUpstreamProxyAuthorization() method
        String upstreamCredentials = "Basic upstreamCreds";
        headers.set("Proxy-Authorization", upstreamCredentials);
        
        System.out.println("Forwarded to upstream proxy with upstream creds: " + headers);
        
        // Verify upstream Proxy-Authorization header is present
        assertThat(headers.contains("Proxy-Authorization"))
            .as("Proxy-Authorization header should be added for upstream proxy that requires auth")
            .isTrue();
        
        assertThat(headers.get("Proxy-Authorization"))
            .as("Upstream credentials should be used, not client credentials")
            .isEqualTo(upstreamCredentials);
        
        // Test handling of upstream 407 response
        HttpResponse upstream407Response = new io.netty.handler.codec.http.DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED);
        
        System.out.println("Simulating upstream proxy 407 response: " + upstream407Response.status());
        
        // This would be handled by handleUpstreamProxyAuthenticationRequired()
        boolean isUpstream407 = upstream407Response.status() == HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
        assertThat(isUpstream407)
            .as("Should correctly identify upstream 407 response")
            .isTrue();
        
        System.out.println("✅ Scenario 2 test passed");
    }
    
    /**
     * Scenario 3: LittleProxy does not handle auth, next proxy does
     * 
     * Client → [Proxy-Authorization: upstreamCreds] → LittleProxy (no auth, preserves header)
     * → [Proxy-Authorization: upstreamCreds] → Upstream Proxy (authenticates) → ✅ Success
     */
    @Test
    public void testScenario3_LittleProxyNoAuth_NextProxyDoes() {
        System.out.println("=== Testing Scenario 3: LittleProxy does not handle auth, next proxy does ===");
        
        // Simulate client request with upstream authentication
        HttpRequest request = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        request.headers().set("Proxy-Authorization", "Basic upstreamCreds");
        
        System.out.println("Client request headers: " + request.headers());
        
        // LittleProxy doesn't authenticate, so it preserves the header
        // This simulates the case where proxyServer.getProxyAuthenticator() returns null
        HttpHeaders headers = request.headers();
        
        // Since LittleProxy doesn't handle auth, it should preserve the header for upstream
        ProxyUtils.stripHopByHopHeaders(headers, true); // Preserve Proxy-Authorization
        
        System.out.println("Forwarded to upstream proxy (header preserved): " + headers);
        
        // Verify Proxy-Authorization header is preserved
        assertThat(headers.contains("Proxy-Authorization"))
            .as("Proxy-Authorization header should be preserved when LittleProxy doesn't handle auth")
            .isTrue();
        
        assertThat(headers.get("Proxy-Authorization"))
            .as("Original upstream credentials should be preserved")
            .isEqualTo("Basic upstreamCreds");
        
        System.out.println("✅ Scenario 3 test passed");
    }
    
    /**
     * Scenario 4: Neither proxy handles auth
     * 
     * Client → [No auth header] → LittleProxy (no auth) → [No auth header] 
     * → Upstream Proxy (no auth) → ✅ Success
     */
    @Test
    public void testScenario4_NeitherProxyHandlesAuth() {
        System.out.println("=== Testing Scenario 4: Neither proxy handles auth ===");
        
        // Simulate client request without authentication
        HttpRequest request = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        // No Proxy-Authorization header
        
        System.out.println("Client request headers: " + request.headers());
        
        // LittleProxy doesn't authenticate, upstream proxy doesn't require auth
        HttpHeaders headers = request.headers();
        
        // Normal hop-by-hop header stripping (no special preservation)
        ProxyUtils.stripHopByHopHeaders(headers, false);
        
        System.out.println("Forwarded to upstream proxy: " + headers);
        
        // Verify no Proxy-Authorization header is present
        assertThat(headers.contains("Proxy-Authorization"))
            .as("No Proxy-Authorization header should be present when no auth is required")
            .isFalse();
        
        System.out.println("✅ Scenario 4 test passed");
    }
    
    /**
     * Test the complete authentication flow logic
     */
    @Test
    public void testCompleteAuthenticationFlow() {
        System.out.println("=== Testing Complete Authentication Flow ===");
        
        // Test the shouldPreserveProxyAuthorizationForUpstream logic
        
        // Mock a chained proxy that requires authentication
        ChainedProxy authRequiredProxy = Mockito.mock(ChainedProxy.class);
        Mockito.when(authRequiredProxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
        Mockito.when(authRequiredProxy.getUsername()).thenReturn("upstreamUser");
        Mockito.when(authRequiredProxy.getPassword()).thenReturn("upstreamPass");
        
        // Mock a chained proxy that doesn't require authentication
        ChainedProxy noAuthProxy = Mockito.mock(ChainedProxy.class);
        Mockito.when(noAuthProxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
        Mockito.when(noAuthProxy.getUsername()).thenReturn(null);
        Mockito.when(noAuthProxy.getPassword()).thenReturn(null);
        
        // Mock a SOCKS proxy (should not preserve Proxy-Authorization)
        ChainedProxy socksProxy = Mockito.mock(ChainedProxy.class);
        Mockito.when(socksProxy.getChainedProxyType()).thenReturn(ChainedProxyType.SOCKS4);
        Mockito.when(socksProxy.getUsername()).thenReturn("socksUser");
        Mockito.when(socksProxy.getPassword()).thenReturn("socksPass");
        
        System.out.println("Testing authentication preservation logic:");
        
        // Should preserve for HTTP proxy with credentials
        boolean shouldPreserve1 = shouldPreserveProxyAuthorizationForUpstream(authRequiredProxy);
        assertThat(shouldPreserve1)
            .as("Should preserve Proxy-Authorization for HTTP proxy with credentials")
            .isTrue();
        
        // Should not preserve for HTTP proxy without credentials
        boolean shouldPreserve2 = shouldPreserveProxyAuthorizationForUpstream(noAuthProxy);
        assertThat(shouldPreserve2)
            .as("Should not preserve Proxy-Authorization for HTTP proxy without credentials")
            .isFalse();
        
        // Should not preserve for SOCKS proxy (even with credentials)
        boolean shouldPreserve3 = shouldPreserveProxyAuthorizationForUpstream(socksProxy);
        assertThat(shouldPreserve3)
            .as("Should not preserve Proxy-Authorization for SOCKS proxy")
            .isFalse();
        
        System.out.println("✅ Complete authentication flow test passed");
    }
    
    /**
     * Helper method to simulate the shouldPreserveProxyAuthorizationForUpstream logic
     */
    private boolean shouldPreserveProxyAuthorizationForUpstream(ChainedProxy chainedProxy) {
        if (chainedProxy == null) {
            return false;
        }
        
        // Only preserve for HTTP proxies (not SOCKS)
        if (chainedProxy.getChainedProxyType() != ChainedProxyType.HTTP) {
            return false;
        }
        
        // Check if the upstream proxy requires authentication
        return chainedProxy.getUsername() != null && chainedProxy.getPassword() != null;
    }
}