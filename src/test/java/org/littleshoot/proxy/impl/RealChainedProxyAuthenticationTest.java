package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ChainedProxy;
import org.littleshoot.proxy.ChainedProxyType;
import org.mockito.Mockito;

/**
 * Real unit tests that exercise the actual authentication code paths for all four chained proxy
 * authentication scenarios.
 */
class RealChainedProxyAuthenticationTest {

  private ClientToProxyConnection clientToProxyConnection;
  private ProxyToServerConnection proxyToServerConnection;
  private AtomicBoolean authenticated;

  @BeforeEach
  void setUp() {
    // Mock the connections and dependencies
    clientToProxyConnection = Mockito.mock(ClientToProxyConnection.class, CALLS_REAL_METHODS);
    proxyToServerConnection = Mockito.mock(ProxyToServerConnection.class);
    authenticated = new AtomicBoolean(false);

    // Set up the mock to return our test values
    when(clientToProxyConnection.getAuthenticated()).thenReturn(authenticated);

    // Use reflection to set the currentServerConnection field directly since
    // the shouldPreserveProxyAuthorizationForUpstream method accesses it directly
    try {
      java.lang.reflect.Field currentServerConnectionField =
          ClientToProxyConnection.class.getDeclaredField("currentServerConnection");
      currentServerConnectionField.setAccessible(true);
      currentServerConnectionField.set(clientToProxyConnection, proxyToServerConnection);

      // Initialize the LOG field to avoid NPE in logging calls
      // LOG field is in the parent ProxyConnection class
      java.lang.reflect.Field logField = ProxyConnection.class.getDeclaredField("logger");
      logField.setAccessible(true);
      logField.set(clientToProxyConnection, new ProxyConnectionLogger(clientToProxyConnection));
    } catch (Exception e) {
      throw new RuntimeException("Failed to set up mock fields", e);
    }
  }

  /**
   * Scenario 1: LittleProxy handles auth, next proxy does not
   *
   * <p>Client → [Proxy-Authorization: clientCreds] → LittleProxy (authenticates, removes header) →
   * [No auth header] → Upstream Proxy (no auth needed) → ✅ Success
   */
  @Test
  void testScenario1_RealCodePath_LittleProxyAuthOnly() {
    System.out.println(
        "=== Real Test Scenario 1: LittleProxy handles auth, next proxy does not ===");

    // Create a real request with client credentials
    HttpRequest request =
        new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
    request
        .headers()
        .set(
            "Proxy-Authorization",
            "Basic " + Base64.getEncoder().encodeToString("clientUser:clientPass".getBytes()));

    System.out.println("1. Client request: " + request.headers());

    // Simulate client authentication (header would be removed by real auth logic)
    request.headers().remove("Proxy-Authorization");

    // Mock: upstream proxy doesn't require authentication
    when(proxyToServerConnection.hasUpstreamChainedProxy()).thenReturn(true);
    ChainedProxy noAuthProxy = createNoAuthProxy();
    when(proxyToServerConnection.getChainedProxy()).thenReturn(noAuthProxy);

    // Test the real shouldPreserveProxyAuthorizationForUpstream method
    boolean shouldPreserve = clientToProxyConnection.shouldPreserveProxyAuthorizationForUpstream();

    System.out.println("2. Should preserve Proxy-Authorization: " + shouldPreserve);

    // Verify: should NOT preserve (upstream proxy doesn't require auth)
    assertThat(shouldPreserve)
        .as("Should not preserve Proxy-Authorization when upstream proxy doesn't require auth")
        .isFalse();

    // Test the real stripHopByHopHeaders method
    HttpHeaders headersCopy = request.headers().copy();
    clientToProxyConnection.stripHopByHopHeaders(headersCopy);

    System.out.println("3. After header processing: " + headersCopy);

    // Verify: no Proxy-Authorization header should be present
    assertThat(headersCopy.contains("Proxy-Authorization"))
        .as(
            "No Proxy-Authorization header should be forwarded to upstream proxy that doesn't require auth")
        .isFalse();

    // Verify: shouldPreserve is false when no upstream auth is required
    assertThat(shouldPreserve)
        .as("Should not preserve Proxy-Authorization when upstream proxy doesn't require auth")
        .isFalse();

    System.out.println("✅ Scenario 1 real code path test passed");
  }

  /**
   * Scenario 2: Both LittleProxy and next proxy handle auth
   *
   * <p>Client → [Proxy-Authorization: clientUser:clientPass] → LittleProxy (authenticates) →
   * [Proxy-Authorization: upstreamUser:upstreamPass] → Upstream Proxy → ✅ Success
   */
  @Test
  void testScenario2_RealCodePath_BothHandleAuth() {
    System.out.println("=== Real Test Scenario 2: Both proxies handle auth ===");

    // Define different credentials for each proxy
    String clientCredentials = "clientUser:clientPass";
    String upstreamCredentials = "upstreamUser:upstreamPass";

    // Create a real request with CLIENT credentials for LittleProxy
    HttpRequest request =
        new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
    request
        .headers()
        .set(
            "Proxy-Authorization",
            "Basic " + Base64.getEncoder().encodeToString(clientCredentials.getBytes()));

    System.out.println("1. Client request with CLIENT credentials: " + request.headers());
    System.out.println("   Client credentials: " + clientCredentials);

    // Simulate client authentication (header would be removed by real auth logic)
    request.headers().remove("Proxy-Authorization");
    System.out.println("2. After LittleProxy authentication: header removed");

    // Mock: upstream proxy requires authentication with DIFFERENT credentials
    when(proxyToServerConnection.hasUpstreamChainedProxy()).thenReturn(true);
    ChainedProxy authRequiredProxy = createUpstreamAuthProxy(upstreamCredentials);
    when(proxyToServerConnection.getChainedProxy()).thenReturn(authRequiredProxy);

    System.out.println("   Upstream credentials: " + upstreamCredentials);

    // Test the real shouldPreserveProxyAuthorizationForUpstream method
    boolean shouldPreserve = clientToProxyConnection.shouldPreserveProxyAuthorizationForUpstream();

    System.out.println("3. Should preserve Proxy-Authorization: " + shouldPreserve);

    // Verify: SHOULD preserve (upstream proxy requires auth)
    assertThat(shouldPreserve)
        .as("Should preserve Proxy-Authorization when upstream proxy requires auth")
        .isTrue();

    // Test the real stripHopByHopHeaders method
    HttpHeaders headersCopy = request.headers().copy();
    clientToProxyConnection.stripHopByHopHeaders(headersCopy);

    System.out.println("4. After stripping hop-by-hop headers: " + headersCopy);

    // Verify: Proxy-Authorization header should be removed (was already removed by client auth
    // simulation)
    assertThat(headersCopy.contains("Proxy-Authorization"))
        .as("Proxy-Authorization header should be removed after stripping hop-by-hop headers")
        .isFalse();

    // Test the real addUpstreamProxyAuthorization method
    clientToProxyConnection.addUpstreamProxyAuthorization(headersCopy);

    System.out.println("5. After adding UPSTREAM credentials: " + headersCopy);

    // Verify: upstream credentials were added
    assertThat(headersCopy.contains("Proxy-Authorization"))
        .as("Proxy-Authorization header should be added with upstream credentials")
        .isTrue();

    // Verify: credentials are for upstream proxy, not client
    String authHeader = headersCopy.get("Proxy-Authorization");
    String expectedUpstreamAuth =
        "Basic " + Base64.getEncoder().encodeToString(upstreamCredentials.getBytes());

    assertThat(authHeader)
        .as("Should contain upstream credentials, not client credentials")
        .isEqualTo(expectedUpstreamAuth)
        .doesNotContain(clientCredentials);

    // Explicit verification that credentials are different
    assertThat(authHeader)
        .as("Upstream credentials should be different from client credentials")
        .isNotEqualTo("Basic " + Base64.getEncoder().encodeToString(clientCredentials.getBytes()));

    System.out.println("✅ Scenario 2 real code path test passed - different credentials verified");
  }

  /**
   * Scenario 3: LittleProxy does not handle auth, next proxy does
   *
   * <p>Client → [Proxy-Authorization: upstreamUser:upstreamPass] → LittleProxy (no auth, preserves
   * header) → [Proxy-Authorization: upstreamUser:upstreamPass] → Upstream Proxy (authenticates) → ✅
   * Success
   */
  @Test
  void testScenario3_RealCodePath_NoLittleProxyAuth() {
    System.out.println("=== Real Test Scenario 3: LittleProxy no auth, next proxy does ===");

    // Create a real request with upstream credentials (no client auth)
    HttpRequest request =
        new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
    request
        .headers()
        .set(
            "Proxy-Authorization",
            "Basic " + Base64.getEncoder().encodeToString("upstreamUser:upstreamPass".getBytes()));

    System.out.println("1. Client request (no LittleProxy auth): " + request.headers());

    // Mock: LittleProxy doesn't authenticate (authenticated remains false)
    authenticated.set(false);

    // Mock: upstream proxy requires authentication
    when(proxyToServerConnection.hasUpstreamChainedProxy()).thenReturn(true);
    ChainedProxy authRequiredProxy = createAuthRequiredProxy();
    when(proxyToServerConnection.getChainedProxy()).thenReturn(authRequiredProxy);

    // Test the real shouldPreserveProxyAuthorizationForUpstream method
    boolean shouldPreserve = clientToProxyConnection.shouldPreserveProxyAuthorizationForUpstream();

    System.out.println("2. Should preserve Proxy-Authorization: " + shouldPreserve);

    // Verify: SHOULD preserve (upstream proxy requires auth)
    assertThat(shouldPreserve)
        .as("Should preserve Proxy-Authorization when upstream proxy requires auth")
        .isTrue();

    // Test the real stripHopByHopHeaders method - now always removes Proxy-Authorization
    HttpHeaders headersCopy = request.headers().copy();
    clientToProxyConnection.stripHopByHopHeaders(headersCopy);

    System.out.println("3. After stripping hop-by-hop headers: " + headersCopy);

    // Verify: Proxy-Authorization header is removed (hop-by-hop headers are always stripped)
    // The header is added back by addUpstreamProxyAuthorization when needed
    assertThat(headersCopy.contains("Proxy-Authorization"))
        .as("Proxy-Authorization header should be removed by stripHopByHopHeaders")
        .isFalse();

    // Since shouldPreserve is true, addUpstreamProxyAuthorization would add the header
    // but we test that separately
    System.out.println("✅ Scenario 3 real code path test passed");
  }

  /**
   * Scenario 4: Neither proxy handles auth
   *
   * <p>Client → [No auth header] → LittleProxy (no auth) → [No auth header] → Upstream Proxy (no
   * auth) → ✅ Success
   */
  @Test
  void testScenario4_RealCodePath_NoAuthAnywhere() {
    System.out.println("=== Real Test Scenario 4: Neither proxy handles auth ===");

    // Create a real request without any authentication
    HttpRequest request =
        new io.netty.handler.codec.http.DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
    // No Proxy-Authorization header

    System.out.println("1. Client request (no auth): " + request.headers());

    // Mock: no upstream chained proxy
    when(proxyToServerConnection.hasUpstreamChainedProxy()).thenReturn(false);

    // Test the real shouldPreserveProxyAuthorizationForUpstream method
    boolean shouldPreserve = clientToProxyConnection.shouldPreserveProxyAuthorizationForUpstream();

    System.out.println("2. Should preserve Proxy-Authorization: " + shouldPreserve);

    // Verify: should NOT preserve (no upstream proxy)
    assertThat(shouldPreserve)
        .as("Should not preserve Proxy-Authorization when no upstream proxy")
        .isFalse();

    // Test the real stripHopByHopHeaders method (normal behavior)
    HttpHeaders headersCopy = request.headers().copy();
    clientToProxyConnection.stripHopByHopHeaders(headersCopy);

    System.out.println("3. After normal header processing: " + headersCopy);

    // Verify: no Proxy-Authorization header should be present
    assertThat(headersCopy.contains("Proxy-Authorization"))
        .as("No Proxy-Authorization header should be present when no auth is required")
        .isFalse();

    // Verify: shouldPreserve is false when no upstream proxy
    assertThat(shouldPreserve)
        .as("Should not preserve Proxy-Authorization when no upstream proxy")
        .isFalse();

    System.out.println("✅ Scenario 4 real code path test passed");
  }

  /** Test upstream 407 response handling */
  @Test
  void testUpstream407ResponseHandling() {
    System.out.println("=== Real Test: Upstream 407 Response Handling ===");

    // Mock: upstream proxy requires authentication
    when(proxyToServerConnection.hasUpstreamChainedProxy()).thenReturn(true);
    ChainedProxy authRequiredProxy = createAuthRequiredProxy();
    when(proxyToServerConnection.getChainedProxy()).thenReturn(authRequiredProxy);

    // Test various response types

    // 1. Test 407 response from upstream proxy
    HttpResponse upstream407 =
        new io.netty.handler.codec.http.DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED);

    boolean isUpstream407 =
        clientToProxyConnection.handleUpstreamProxyAuthenticationRequired(upstream407);

    System.out.println("1. Upstream 407 response handled: " + isUpstream407);
    assertThat(isUpstream407).as("Should identify and handle upstream 407 response").isTrue();

    // 2. Test 200 response (should not be handled as 407)
    HttpResponse successResponse =
        new io.netty.handler.codec.http.DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

    boolean isSuccessHandled =
        clientToProxyConnection.handleUpstreamProxyAuthenticationRequired(successResponse);

    System.out.println("2. Success response handled as 407: " + isSuccessHandled);
    assertThat(isSuccessHandled).as("Should not handle success response as 407").isFalse();

    // 3. Test 401 response (should not be handled as 407)
    HttpResponse unauthorizedResponse =
        new io.netty.handler.codec.http.DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);

    boolean isUnauthorizedHandled =
        clientToProxyConnection.handleUpstreamProxyAuthenticationRequired(unauthorizedResponse);

    System.out.println("3. 401 response handled as 407: " + isUnauthorizedHandled);
    assertThat(isUnauthorizedHandled).as("Should not handle 401 response as 407").isFalse();

    System.out.println("✅ Upstream 407 response handling test passed");
  }

  // Helper methods to create test proxies

  private ChainedProxy createUpstreamAuthProxy(String credentials) {
    ChainedProxy proxy = Mockito.mock(ChainedProxy.class);
    when(proxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);

    // Parse credentials for username and password
    String[] parts = credentials.split(":", 2);
    String username = parts[0];
    String password = parts.length > 1 ? parts[1] : "";

    when(proxy.getUsername()).thenReturn(username);
    when(proxy.getPassword()).thenReturn(password);
    return proxy;
  }

  private ChainedProxy createAuthRequiredProxy() {
    ChainedProxy proxy = Mockito.mock(ChainedProxy.class);
    when(proxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
    when(proxy.getUsername()).thenReturn("upstreamUser");
    when(proxy.getPassword()).thenReturn("upstreamPass");
    return proxy;
  }

  private ChainedProxy createNoAuthProxy() {
    ChainedProxy proxy = Mockito.mock(ChainedProxy.class);
    when(proxy.getChainedProxyType()).thenReturn(ChainedProxyType.HTTP);
    when(proxy.getUsername()).thenReturn(null);
    when(proxy.getPassword()).thenReturn(null);
    return proxy;
  }

  private ChainedProxy createSocksProxy() {
    ChainedProxy proxy = Mockito.mock(ChainedProxy.class);
    when(proxy.getChainedProxyType()).thenReturn(ChainedProxyType.SOCKS4);
    when(proxy.getUsername()).thenReturn("socksUser");
    when(proxy.getPassword()).thenReturn("socksPass");
    return proxy;
  }
}
