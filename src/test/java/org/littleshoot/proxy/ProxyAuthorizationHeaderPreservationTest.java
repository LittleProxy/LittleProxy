package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.ProxyUtils;

/**
 * Unit test to verify that Proxy-Authorization headers are properly preserved
 * when forwarding to upstream proxies that require authentication.
 */
public class ProxyAuthorizationHeaderPreservationTest {

    @Test
    public void testProxyAuthorizationHeaderPreservedWhenNeeded() {
        HttpRequest request = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/test");
        
        // Add Proxy-Authorization header (as the client would)
        request.headers().set("Proxy-Authorization", "Basic dXNlcjpwYXNz");
        
        System.out.println("Original headers: " + request.headers());
        
        // Test the new method that preserves Proxy-Authorization
        ProxyUtils.stripHopByHopHeaders(request.headers(), true);
        
        System.out.println("After stripping hop-by-hop headers (preserving Proxy-Authorization): " + request.headers());
        
        // The Proxy-Authorization header should still be present
        assertThat(request.headers().contains("Proxy-Authorization"))
            .as("Proxy-Authorization header should be preserved when preserveProxyAuthorization=true")
            .isTrue();
    }
    
    @Test
    public void testProxyAuthorizationHeaderRemovedWhenNotNeeded() {
        HttpRequest request = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/test");
        
        // Add Proxy-Authorization header (as the client would)
        request.headers().set("Proxy-Authorization", "Basic dXNlcjpwYXNz");
        
        System.out.println("Original headers: " + request.headers());
        
        // Test the original method that removes Proxy-Authorization
        ProxyUtils.stripHopByHopHeaders(request.headers(), false);
        
        System.out.println("After stripping hop-by-hop headers (not preserving Proxy-Authorization): " + request.headers());
        
        // The Proxy-Authorization header should be removed
        assertThat(request.headers().contains("Proxy-Authorization"))
            .as("Proxy-Authorization header should be removed when preserveProxyAuthorization=false")
            .isFalse();
    }
    
    @Test
    public void testOtherHopByHopHeadersStillRemoved() {
        HttpRequest request = new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/test");
        
        // Add various hop-by-hop headers
        request.headers().set("Proxy-Authorization", "Basic dXNlcjpwYXNz");
        request.headers().set("Connection", "keep-alive");
        request.headers().set("Keep-Alive", "timeout=5");
        request.headers().set("Proxy-Authenticate", "Basic realm=\"test\"");
        
        System.out.println("Original headers: " + request.headers());
        
        // Test that only Proxy-Authorization is preserved
        ProxyUtils.stripHopByHopHeaders(request.headers(), true);
        
        System.out.println("After stripping hop-by-hop headers (preserving Proxy-Authorization): " + request.headers());
        
        // Proxy-Authorization should be preserved
        assertThat(request.headers().contains("Proxy-Authorization"))
            .as("Proxy-Authorization header should be preserved")
            .isTrue();
        
        // Other hop-by-hop headers should be removed
        assertThat(request.headers().contains("Connection"))
            .as("Connection header should be removed")
            .isFalse();
        
        assertThat(request.headers().contains("Keep-Alive"))
            .as("Keep-Alive header should be removed")
            .isFalse();
            
        assertThat(request.headers().contains("Proxy-Authenticate"))
            .as("Proxy-Authenticate header should be removed")
            .isFalse();
    }
}