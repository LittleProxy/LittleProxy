# Plan: Extend Shared Connection Pooling to MITM / HTTPS Upstreams

Date: 2026-07-29
Author: opencode analysis
Based on: PR #724 (clescot), issue #83

## Current State

The shared `ServerConnectionPool` (PR #724, merged) is **off by default** and explicitly excludes four scenarios in `ClientToProxyConnection.doReadHTTPInitial()` (`src/main/java/org/littleshoot/proxy/impl/ClientToProxyConnection.java:282-287`):

```java
boolean useSharedPool =
    usePool
        && !ProxyUtils.isCONNECT(httpRequest)
        && !isTunneling()
        && !isMitming()
        && !ProxyUtils.isSwitchingToWebSocketProtocol(httpRequest);
```

- CONNECT requests always get dedicated connections in `serverConnectionsByHostAndPort`
- After MITM is established, all subsequent HTTP requests from that client use the same dedicated upstream connection
- Upstream TLS is established once per MITM session and never shared

## Phase 1 — Cross-Client MITM Upstream Pooling

**Goal:** Reuse upstream TLS connections across different clients' MITM sessions.
When client A disconnects, their upstream connection to `example.com:443` goes back
to the pool. Client B CONNECTing to the same host picks it up — skipping a TCP + TLS
handshake.

### Changes

#### 1a. Add config flag `poolSharedMitmConnections` (default: false)

Files: `DefaultHttpProxyServerConfig.java`, `DefaultHttpProxyServerBootstrap.java`

Add a boolean field that controls whether MITM connections can use the shared pool.
Default `false` preserves existing behavior.

```java
// DefaultHttpProxyServerConfig.java
private boolean poolSharedMitmConnections = false;
```

```java
// DefaultHttpProxyServerBootstrap.java
public HttpProxyServerBootstrap withPoolSharedMitmConnections(boolean enabled) {
    this.poolSharedMitmConnections = enabled;
    return this;
}
```

Also add to `ServerConnectionPoolConfig.java` since that's the config object passed
to `DefaultHttpProxyServer`.

#### 1b. Remove `!isMitming()` from pool guard (behind flag)

File: `ClientToProxyConnection.java:282-287`

```java
boolean useSharedPool =
    usePool
        && !ProxyUtils.isCONNECT(httpRequest)
        && !isTunneling()
        // && !isMitming()  // REMOVED — now controlled by poolSharedMitmConnections flag
        && !ProxyUtils.isSwitchingToWebSocketProtocol(httpRequest);
```

But only when `poolSharedMitmConnections` is true. The flag check happens elsewhere
(in `ensureServerConnection` or at the call site) — when `poolSharedMitmConnections`
is false, fall through to the existing `serverConnectionsByHostAndPort` path.

#### 1c. Create MITM server connections via pool during CONNECT

File: `ClientToProxyConnection.java`

During CONNECT handling (the section that creates the initial MITM server connection),
replace `ProxyToServerConnection.create(...)` + `serverConnectionsByHostAndPort.put(...)`
with `pool.getOrCreateConnection(...)`.

**Key insight:** The CONNECT request itself triggers `initializeConnectionFlow()` which
builds the MITM flow (EncryptChannel → RespondCONNECTSuccessful → MitmEncryptClientChannel).
The pool's `getOrCreateConnection()` returns a connection that then goes through
`connectAndWrite()` → `connect()` → `initializeConnectionFlow()`. This is the same
pattern used for regular HTTP pooled connections.

For a **new** connection (first client to a host):
- Pool creates via `createForPool()`
- Connection flow runs: `ConnectChannel → EncryptChannel(serverSslEngine) → RespondCONNECTSuccessful → MitmEncryptClientChannel`
- Same as current MITM setup

For a **reused** connection (subsequent clients to same host):
- Pool returns idle connection with connected channel + existing sslEngine
- Connection flow needs to **skip** `ConnectChannel` and `EncryptChannel`
- Flow runs: `RespondCONNECTSuccessful → MitmEncryptClientChannel`
- `MitmEncryptClientChannel` uses `sslEngine.getSession()` — still valid from previous handshake

#### 1d. Return MITM connections to pool on client disconnect

File: `ProxyToServerConnection.java:disconnected()` (lines 643-665)

Currently:
```java
if (connectionPool != null) {
    connectionPool.removeConnection(this);  // removes and destroys
    if (channel != null) {
        connectionPool.drainPendingRequests(channel);
    }
}
```

For pooled MITM connections, change to:
```java
if (connectionPool != null) {
    if (isMitmConnection()) {
        connectionPool.releaseConnection(this);  // return to pool
    } else {
        connectionPool.removeConnection(this);
        if (channel != null) {
            connectionPool.drainPendingRequests(channel);
        }
    }
}
```

The pool's `releaseConnection()` puts the connection into the available queue.
If the channel is still connected, it's reusable.

#### 1e. Skip ConnectChannel and EncryptChannel for reused TLS connections

File: `ProxyToServerConnection.java:initializeConnectionFlow()`

The `ConnectionFlow` is built dynamically. Currently it always starts with
`ConnectChannel` and adds `EncryptChannel` for MITM.

For pooled connections that are already connected and TLS-wired:
- Add a method `boolean isConnectionReady()` that checks `isConnected() && sslEngine != null`
- In `initializeConnectionFlow()`, check this flag at the top
- If true: skip `ConnectChannel` and `EncryptChannel` — connection already established
- If false: proceed as normal (new connection)

The flow step chain for a reused MITM connection:
```
[skip ConnectChannel] → [skip EncryptChannel]
→ (chained proxy steps if applicable — skipped if already done)
→ RespondCONNECTSuccessful → MitmEncryptClientChannel
```

**Important:** For chained proxy encryption (`requiresEncryption()`), the `sslWithServer`
handler is already in the pipeline. Skip adding it again. This requires checking
for the handler in `ProxyConnection.encrypt()`.

#### 1f. `clientSslEngineFor` uses existing SSLSession

File: `ProxyToServerConnection.java:MitmEncryptClientChannel` (line 1458)

No change needed:
```java
proxyServer.getMitmManager().clientSslEngineFor(initialRequest, sslEngine.getSession())
```

On a reused connection, `sslEngine` is the SSLEngine from the original handshake.
`getSession()` returns the already-negotiated `SSLSession`, which is still valid
(it doesn't expire until the connection drops or session timeout elapses).

The session may be invalidated if the upstream server has a very short session
timeout. In that case, `getSession()` returns an invalid session, but the
`SelfSignedMitmManager` ignores it. Production `MitmManager` implementations
would need to handle this gracefully.

#### 1g. Wire new config fields through to server

Files:
- `DefaultHttpProxyServerConfig.java` — add `poolSharedMitmConnections` field
- `ServerConnectionPoolConfig.java` — add `poolSharedMitmConnections` field
- `DefaultHttpProxyServerBootstrap.java` — read in `build()`
- `DefaultHttpProxyServer.java` — pass to relevant decision points

### Tests for Phase 1

New test class: `MitmWithSharedPoolTest extends MitmProxyTest`

```java
class MitmWithSharedPoolTest extends MitmProxyTest {
    @Override
    protected void setUp() {
        proxyServer = bootstrapProxy()
            .withPort(0)
            .withManInTheMiddle(new TestMitmManager())
            .withSharedServerConnectionPool(true)
            .withPoolSharedMitmConnections(true)  // NEW
            .withFiltersSource(...)
            .start();
    }

    @Test
    void testHttpsGetWithPoolEnabled() {
        // Basic MITM + pool: HTTPS GET works
        ResponseInfo response = httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    @Test
    void testUpstreamConnectionReusedAcrossClients() {
        // Two sequential clients to same host: only 1 upstream connection created
        ServerConnectionPool pool = ((DefaultHttpProxyServer) proxyServer).getServerConnectionPool();
        PoolMetrics before = pool.getMetrics();

        // Client 1
        ResponseInfo r1 = httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);
        assertThat(r1.getStatusCode()).isEqualTo(200);

        // Client 2 (same host)
        ResponseInfo r2 = httpGetWithApacheClient(httpsWebHost, DEFAULT_RESOURCE, true, false);
        assertThat(r2.getStatusCode()).isEqualTo(200);

        PoolMetrics after = pool.getMetrics();
        assertThat(after.getTotalConnections() - before.getTotalConnections())
            .as("Should reuse upstream connection across clients")
            .isLessThanOrEqualTo(1);
    }
}
```

Add to `MitmWithChainedProxyTest` variants similarly.

---

## Phase 2 — Per-Request Pooling Within MITM Tunnels

**Goal:** After MITM is established, each HTTP request from the client independently
gets/releases a server connection from the pool. Multiple clients' requests can
share upstream connections concurrently (one request at a time per connection).

### Changes

#### 2a. Remove `serverConnectionsByHostAndPort` for MITM

File: `ClientToProxyConnection.java`

Currently MITM requests look up the dedicated connection:
```java
currentServerConnection = serverConnectionsByHostAndPort.get(serverHostAndPort);
if (currentServerConnection == null) {
    newConnectionRequired = true;
}
```

For Phase 2, all MITM requests go through the pool path instead:
```java
currentServerConnection = pool.getOrCreateConnection(...);
```

This means even sequential requests from the same client may use different
upstream connections (from the pool). This is fine because:
- Each request/response is independent
- HTTP pipelining is already handled by `PendingRequest` tracking
- SSL session is per-connection, but `clientSslEngineFor` was already called

#### 2b. Ensure `markResponseComplete` releases to pool

File: `ProxyToServerConnection.java:markResponseComplete()` (lines 803-821)

This already works for regular HTTP pooled connections. Same path applies to
MITM connections once they're pool-managed:
```java
private void markResponseComplete() {
    this.currentClientConnectionForRequest = null;
    this.currentHttpResponse = null;
    if (connectionPool != null) {
        // Check for pipelined requests
        if (channel != null) {
            PendingRequest nextPending = connectionPool.removePendingRequest(channel);
            if (nextPending != null) {
                this.currentClientConnectionForRequest = nextPending.getClientConnection();
                this.currentHttpRequest = nextPending.getRequest();
                this.currentFilters = nextPending.getFilters();
                return;
            }
        }
        this.currentHttpRequest = null;
        connectionPool.releaseConnection(this);
    } else {
        this.currentHttpRequest = null;
    }
}
```

No code change needed — the path is already there. It just needs to be reachable
from MITM requests.

#### 2c. Handle connection reuse for `MitmEncryptClientChannel`

The `MitmEncryptClientChannel` step runs once during CONNECT setup. After that,
the client's SSL channel is encrypted. Subsequent HTTP requests flow through
`doReadHTTPInitial()` and don't re-enter the CONNECT flow.

However, each time `pool.getOrCreateConnection()` returns a different connection
from the pool, that connection must already have its SSL handshake complete.
This is guaranteed because:
- The connection was established via `createForPool()` during CONNECT or was
  returned from pool after a previous request
- `sslEngine` is preserved across pool borrow/release cycles

**Check for sslEngine validity during borrow:**

In each pool implementation (ConcurrentMap, CommonsPool, Stormpot), before
returning a connection from the available queue:
```java
if (connection.getSslEngine() != null && !connection.getSslEngine().getSession().isValid()) {
    // Session expired — close and create new
    connection.disconnect();
    removeConnection(connection);
    continue;  // try next available
}
```

Add this validation step in `borrowAvailableConnection()` for
`ConcurrentMapServerConnectionPool` and corresponding borrow paths in
`CommonsPoolServerConnectionPool` and `StormpotServerConnectionPool`.

### Tests for Phase 2

```java
@Tag("slow-test")
class MitmWithPerRequestPoolTest extends MitmProxyTest {
    @Override
    protected void setUp() {
        proxyServer = bootstrapProxy()
            .withPort(0)
            .withManInTheMiddle(new TestMitmManager())
            .withSharedServerConnectionPool(true)
            .withPoolSharedMitmConnections(true)
            .withPoolPerRequestInMitm(true)  // NEW Phase 2 flag
            .withFiltersSource(...)
            .start();
    }

    @Test
    void testMultipleClientsShareUpstreamConnections() {
        // 5 concurrent clients hitting same HTTPS host
        // Verify that total upstream connections << 5
    }
}
```

---

## Phase 3 — Pooling for HTTPS Upstream (Chained Proxies)

**Goal:** When the proxy chains to an upstream proxy via HTTPS
(`chainedProxy.requiresEncryption() == true`), the TLS-wrapped connection to
the chained proxy is pooled.

### Changes

#### 3a. Pool key already handles chained proxies

File: `ServerConnectionPool.java:computePoolKey()`

The default method already incorporates the chained proxy address:
```java
default String computePoolKey(String serverHostAndPort,
                               @Nullable InetSocketAddress chainedProxyAddress) {
    if (chainedProxyAddress == null) return serverHostAndPort + ":direct";
    return serverHostAndPort + ":" + chainedProxyAddress.getAddress().getHostAddress()
        + ":" + chainedProxyAddress.getPort();
}
```

This means connections through different chained proxies are isolated in the pool.
No change needed here.

#### 3b. Handle `sslWithServer` handler lifecycle

File: `ProxyConnection.java:encrypt()` (lines 333-352)

When adding an SSL handler for chained proxy encryption:
```java
if (pipeline.get("ssl") == null) {
    pipeline.addFirst("ssl", handler);
} else {
    pipeline.addAfter("ssl", "sslWithServer", handler);
}
```

When a pooled connection is idle and then re-borrowed, the `sslWithServer` handler
is still in the pipeline. The pool's validation should ensure:
1. The connection is still connected (`isConnected()`)
2. The SSL handler is still active (`pipeline.get("sslWithServer") != null`)
3. The SSL session is valid (`sslEngine.getSession().isValid()`)

If any check fails, the connection should be evicted from the pool.

#### 3c. Skip chained-proxy connection flow steps for reused connections

File: `ProxyToServerConnection.java:initializeConnectionFlow()`

For a reused pooled connection that already went through chained proxy setup:
- Skip `EncryptChannel(newChainedProxySslEngine())` — already encrypted
- Skip SOCKS handshake steps — already done
- Skip `HTTPCONNECTWithChainedProxy` — already done

Detection: check `pipeline.get("sslWithServer") != null` to determine if
chained proxy encryption was already established.

### Tests for Phase 3

Extend `MitmWithEncryptedTCPChainedProxyTest` to enable pooling and verify
connection reuse across clients through the same chained proxy.

---

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| **TLS session expiry.** Idle connections may have SSL sessions that expire (configurable via SessionContext). | Pool idle timeout should be ≤ TLS session timeout. Validate session validity on borrow. |
| **`clientSslEngineFor` cert mismatch.** Production `MitmManager`s may depend on the exact server cert of a specific connection rather than just the host. | All TLS connections to the same host receive the same server certificate. The SSLSession is inspected for cert chain, not for cryptographic binding. |
| **Connection flow skipping.** Skipping ConnectChannel and EncryptChannel for reused connections introduces new untested logic paths. | Each skipped step needs explicit test coverage. The `isConnected()` + `sslEngine != null` guards are straightforward. |
| **Pipeline state corruption.** Returning a connection to pool then borrowing it for a different client may leave stale handlers. | Pool `releaseConnection` already cleans up per-request state: `currentClientConnectionForRequest = null`, `currentHttpRequest = null`. Need to verify SSL handler is not removed. |
| **HTTP pipelining across clients.** If client A sends pipelined requests and client B borrows the connection mid-pipeline. | PendingRequest queue is per-channel. When connection returns to pool, all pending requests must be drained. This already happens in `drainPendingRequests`. |

## Dependency Graph

```
Phase 1 ──→ Phase 2 ──→ Phase 3
  │                     
  └──────────────────→ Phase 3 (partial)
```

Phases can be implemented independently, but Phase 2 requires the pool-awareness
introduced in Phase 1. Phase 3 builds on Phase 1's connection-flow-skipping
infrastructure.

## Configuration Flags Summary

| Flag | Default | Phase | Description |
|------|---------|-------|-------------|
| `useSharedServerConnectionPool` | false | — (existing) | Master switch for shared pool |
| `poolSharedMitmConnections` | false | 1 | Allow MITM connections to use shared pool |
| `poolPerRequestInMitm` | false | 2 | Per-request (not per-session) pooling inside MITM tunnels |

## Metric Tracking

Add to `PoolMetrics`:

```java
private final int sslHandshakesSaved;  // Phase 1
private final int mitmConnectionsShared; // Phase 2
```

Each pool implementation tracks how many times a connection was reused for MITM,
saving a TLS handshake. Exposed via `PoolMetrics` and optionally JMX.
