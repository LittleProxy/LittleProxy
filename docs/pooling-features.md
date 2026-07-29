# Server Connection Pooling — Full Feature Set

This document describes **all** features on this branch that are not present in the
main branch. The branch introduces a complete shared server connection pooling
infrastructure (based on the design from PR #724) and extends it with MITM / HTTPS
upstream support.

---

## Background

On the main branch, every client connection gets its own dedicated
`ProxyToServerConnection`. When client A and client B both connect to
`http://example.com`, two separate TCP sockets are opened to the same server.
When the traffic is MITM'd HTTPS, the upstream TLS connection is tied to the
client's session for its entire lifetime and discarded on disconnect.

This branch addresses the connection explosion problem by introducing a **shared
server connection pool**, and extends it to HTTPS upstream connections intercepted
via MITM.

---

## Base Connection Pooling (PR #724 Infrastructure)

The entire pool infrastructure is new on this branch. On the main branch, all
connections are created with `ProxyToServerConnection.create(...)` and tracked in a
per-client `serverConnectionsByHostAndPort` map.

### New Interfaces and Classes

| Type | File | Purpose |
|---|---|---|
| Interface | `ServerConnectionPool` | Contract for pooling server connections |
| Config | `ServerConnectionPoolConfig` | Configuration bean (pool type, sizes, timeouts) |
| Config | `DefaultHttpProxyServerConfig` | Server-level config carrier that includes pool config |
| Metrics | `PoolMetrics` | Active/idle/borrow/return/eviction counters |
| Model | `PendingRequest` | Tracks a request awaiting a response (for HTTP pipelining) |
| Enum | `ServerConnectionPoolType` | `CONCURRENT_MAP`, `COMMONS_POOL2`, `STORMPOT` |

### Pool Implementations

Three interchangeable backends, selected via `ServerConnectionPoolType`:

| Pool type | Class | Approach | Dependencies |
|---|---|---|---|
| `CONCURRENT_MAP` | `ConcurrentMapServerConnectionPool` | `ConcurrentHashMap` + per-host `Queue` of available connections | None (pure Netty/Java) |
| `COMMONS_POOL2` | `CommonsPoolServerConnectionPool` | Apache Commons Pool 2 `PooledObjectFactory` | `commons-pool2` |
| `STORMPOT` | `StormpotServerConnectionPool` | Stormpot `Pool` with inline allocator | `stormpot` |

All three:
- Implement `getOrCreateConnection(host, chainedProxyAddr, client, filters, request)` +
  `releaseConnection(connection)` + `removeConnection(connection)` + `closeAll()`
- Track pending requests per channel for HTTP pipelining support
- Enforce per-host and global connection limits
- Support idle timeout eviction and optional connection validation on borrow
- Expose `getMetrics()` returning active/idle/total connections and cumulative operation counts

### `ServerConnectionPool` Interface

```java
ProxyToServerConnection getOrCreateConnection(
    String serverHostAndPort,
    @Nullable InetSocketAddress chainedProxyAddress,
    ClientToProxyConnection clientConnection,
    HttpFilters initialFilters,
    HttpRequest initialHttpRequest);

void releaseConnection(ProxyToServerConnection connection);
void removeConnection(ProxyToServerConnection connection);
void registerPendingRequest(Channel, ClientToProxyConnection, HttpRequest, HttpFilters);
PendingRequest removePendingRequest(Channel);
PendingRequest peekPendingRequest(Channel);
void drainPendingRequests(Channel);
void closeAll();

PoolMetrics getMetrics();

// Computes a compound pool key: "host:port:<chained-proxy-address>"
default String computePoolKey(String serverHostAndPort,
                               @Nullable InetSocketAddress chainedProxyAddress);
```

Pool keys incorporate the chained proxy address, so connections through different
upstream proxies are isolated even when the target host is the same.

### How Plain HTTP Pooling Works

In `ClientToProxyConnection.doReadHTTPInitial()`, the selection logic was changed
from a simple `serverConnectionsByHostAndPort.get(serverHostAndPort)` to a decision
tree:

```java
boolean useSharedPool =
    usePool
        && !ProxyUtils.isCONNECT(httpRequest)
        && !isTunneling()
        && !isMitming()
        && !ProxyUtils.isSwitchingToWebSocketProtocol(httpRequest);
```

When `useSharedPool` is true, `pool.getOrCreateConnection(...)` is called instead of
`ProxyToServerConnection.create(...)`. The pool either returns an idle connection or
creates a new one via `ProxyToServerConnection.createForPool(...)`, which attaches
the `connectionPool` reference so the connection knows it is pool-managed.

**Excluded from pooling:** Tunneling connections (non-MITM CONNECT, raw TCP tunnels)
and WebSocket protocol upgrades are never pooled. Tunneling has no HTTP request/response
boundary to trigger pool release — the connection stays dedicated for the tunnel's
lifetime. WebSocket connections replace HTTP codecs with raw frame handlers after the
upgrade handshake, so they cannot be returned to the pool. Both always use
`serverConnectionsByHostAndPort` with dedicated `ProxyToServerConnection.create()`.

On the response path, `markResponseComplete()` calls
`connectionPool.releaseConnection(this)`, returning the connection to the available
queue. HTTP pipelining is handled via `registerPendingRequest` /
`removePendingRequest` — when a pipelined response arrives, the next pending request
is dequeued and routed.

### Connection Lifecycle for Pooled Connections

| Event | Non-pooled (main branch) | Pooled (this branch) |
|---|---|---|
| New request arrives | `ProxyToServerConnection.create()` | `pool.getOrCreateConnection()` → creates or borrows |
| Request sent | Stored in `currentHttpRequest` | Registered as `PendingRequest` in pool |
| Response received | Forwarded to client | `removePendingRequest()` → forwarded to correct client |
| Response complete | Connection stays in `AWAITING_INITIAL` | `releaseConnection()` → returned to pool |
| Server disconnect | `clientConnection.serverDisconnected()` | `pool.removeConnection()` + drain pending |
| Client disconnect | `serverConnection.disconnect()` (close) | `serverConnection.disconnect()` → `pool.removeConnection()` via `channelInactive` |

### New Builder Methods on `HttpProxyServerBootstrap`

```java
.withSharedServerConnectionPool(boolean)       // master switch
.withServerConnectionPoolType(ServerConnectionPoolType)  // CONCURRENT_MAP default
.withMaxConnectionsPerHost(int)                 // default 10
.withMaxConnections(int)                        // default 200
.withPoolIdleTimeout(Duration)                  // null = no idle eviction
```

### How Pool Configuration Reaches the Server

The configuration flows through three layers:

1. `DefaultHttpProxyServerBootstrap` stores raw builder fields
2. `build()` creates a `ServerConnectionPoolConfig` and a `DefaultHttpProxyServerConfig`
3. `DefaultHttpProxyServer` reads the config on construction

Properties file parsing in `DefaultHttpProxyServerBootstrap(Properties props)` maps
each key:

```properties
use_shared_server_connection_pool=true
server_connection_pool_type=COMMONS_POOL2|CONCURRENT_MAP|STORMPOT
max_connections_per_host=10
max_total_connections=200
```

### Refactoring: `DefaultHttpProxyServerConfig`

A new `DefaultHttpProxyServerConfig` class was extracted to carry all server
configuration as a single object. The old pattern of passing individual fields
through the bootstrap → server constructor was replaced with a config object,
enabling cleaner cloning and property-based construction. This class holds
~25 fields including the `ServerConnectionPoolConfig`.

### Changes to `ClientToProxyConnection`

- **Request routing**: `doReadHTTPInitial()` now branches on `useSharedPool`. The
  pooled path calls `pool.getOrCreateConnection()` with the resolved chained proxy
  address.
- **Connection tracking**: Pooled connections are not stored in
  `serverConnectionsByHostAndPort` (they live in the pool instead).
- **Backpressure** (`becameSaturated` / `becameWritable`): Both methods now also
  check `currentServerConnection` (the transient reference set per request) in
  addition to the `serverConnectionsByHostAndPort` values. This is necessary
  because pooled connections are not in that map.
- **`serverBecameWriteable`**: Now also checks `currentServerConnection` for
  saturation before resuming client reads.
- **`disconnected()`**: Now handles pooled connections by releasing them to the
  pool instead of disconnecting them.
- **`recordClientConnected()`**: Now called from `requestRead()` callback rather
  than during CONNECT setup, fixing a timing issue with pooled connections.
- **`getClientAddress()`**: Fixed a `ClassCastException` when `remoteAddress()`
  returns a non-`InetSocketAddress` type.

### Changes to `ProxyToServerConnection`

- **`connectionPool` field**: All pooled connections carry a reference back to
  their pool.
- **`createForPool()` static factory**: Creates a connection with pool awareness,
  resolving chained proxies and filters the same way as the non-pooled path.
- **`getClientConnection()` method**: Routes responses to the correct client.
  For pooled connections, uses the per-request `currentClientConnectionForRequest`
  field (set before each write) instead of the constructor-injected
  `clientConnection` reference.
- **`write()` method**: When the connection is in pool-managed CONNECT reuse
  state (not `DISCONNECTED` but needs a new flow), triggers `connectAndWrite()`
  for the CONNECT request.
- **`connectionSucceeded()`**: Explicitly releases the initial request reference
  to prevent memory leaks with pooled connections (where `initialRequest` is
  retained).
- **`disconnected()`**: Pool-managed connections call
  `connectionPool.removeConnection(this)` + `drainPendingRequests(channel)` before
  notifying the client.
- **`readRaw()`**: Uses `getClientConnection()` instead of `clientConnection`.
- **All event recording methods** (`recordServerConnected`, `recordServerDisconnected`,
  `recordConnectionSaturated`, etc.): Use `getClientConnection()` instead of
  `clientConnection` to route activity tracker events to the correct client.
- **`SendProxyProtocolHeader`**: Fixed to handle IPv6 addresses by selecting
  `HAProxyProxiedProtocol.TCP6` when either endpoint uses an `Inet6Address`.

### New `PendingRequest` Class

A simple holder for a client connection, HTTP request, and filters, stored in a
per-channel FIFO queue to support HTTP pipelining over pooled connections:

```java
class PendingRequest {
    ClientToProxyConnection getClientConnection();
    HttpRequest getRequest();
    HttpFilters getFilters();
}
```

---

## HTTP-Only Pooling Scenario

When the proxy handles only plain HTTP (no MITM manager, no CONNECT), pooling is fully
determined by `useSharedServerConnectionPool`:

```java
HttpProxyServer server = DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .withSharedServerConnectionPool(true)
    .start();
```

### What gets pooled

All non-CONNECT, non-tunneling, non-WebSocket HTTP requests go through the shared
pool. Each request acquires a connection from the pool, the response flows back,
and `markResponseComplete()` releases the connection immediately.

### Connection lifecycle

```
Client A ── GET /api ──→ pool.getOrCreateConnection() ──→ [idle conn] or [new TCP]
                          ↓
                        response received → markResponseComplete() → releaseConnection()
                          ↓
Client B ── GET /api ──→ pool.getOrCreateConnection() ──→ [same idle conn from A]
```

When the pool has an idle connection for the target `host:port`, it is reused
directly — no new TCP socket. When all connections are busy, the pool either waits
(blocking borrow) or creates a new one up to `maxConnectionsPerHost`.

### Use case

A high-traffic forward proxy serving many clients hitting the same REST APIs.
Without pooling, each request opens a new TCP socket, does a TCP handshake (and
potentially TLS), then tears it down. With pooling, sockets stay alive and are
reused across clients, dramatically reducing latency and server load.

### Backpressure in HTTP-only mode

When using the pool, `currentServerConnection` is set on each request and cleared
on response complete. The `becameSaturated()` / `becameWritable()` / `serverBecameWriteable()`
methods in `ClientToProxyConnection` check this transient reference in addition to the
`serverConnectionsByHostAndPort` map, because pooled connections are not stored in that map.
This ensures backpressure signals flow correctly through the pooled connection to pause/resume
client reads.

### Pool sizing for HTTP-only

For HTTP-only workloads, `maxConnectionsPerHost` (default 10) limits concurrent requests
to any single origin. `maxConnections` (default 200) limits the total across all origins.
If your clients make many concurrent requests to the same server, increase
`maxConnectionsPerHost`. If you proxy to many different origins, increase `maxConnections`.

---

## Mixed HTTP + HTTPS (MITM) Pooling Scenario

When the proxy handles both plain HTTP and MITM'd HTTPS traffic, the pool serves both,
but the MITM paths require additional flags.

### Configuration matrix

```java
// HTTP only: pool is used for all non-CONNECT requests
HttpProxyServer.bootstrap()
    .withPort(8080)
    .withSharedServerConnectionPool(true)
    .start();

// HTTP + MITM cross-client reuse: HTTPS upstream connections survive client sessions
HttpProxyServer.bootstrap()
    .withPort(8080)
    .withManInTheMiddle(myMitmManager)
    .withSharedServerConnectionPool(true)
    .withPoolSharedMitmConnections(true)
    .start();

// HTTP + MITM per-request: full pooling, no dedicated upstream per session
HttpProxyServer.bootstrap()
    .withPort(8080)
    .withManInTheMiddle(myMitmManager)
    .withSharedServerConnectionPool(true)
    .withPoolSharedMitmConnections(true)
    .withPoolPerRequestInMitm(true)
    .start();
```

### How the `useSharedPool` decision works

In `ClientToProxyConnection.doReadHTTPInitial()`, every request goes through a
single decision tree:

```java
boolean usePool = proxyServer.getServerConnectionPool() != null;
boolean isConnect = ProxyUtils.isCONNECT(httpRequest);
boolean poolSharedMitm = usePool && proxyServer.isPoolSharedMitmConnections();
boolean poolPerRequest = usePool && proxyServer.isPoolPerRequestInMitm();

boolean useSharedPool =
    usePool
        && !isTunneling()
        && !ProxyUtils.isSwitchingToWebSocketProtocol(httpRequest)
        && (poolPerRequest || !isMitming())
        && (poolSharedMitm || !isConnect);
```

A request reaches the pool when:
- The pool is enabled (`usePool`)
- It is not a WebSocket upgrade or tunneling request — these are **always excluded**
  because tunneling has no request/response boundary and WebSocket replaces HTTP codecs
  with raw frame handlers, making pool return impossible
- **For CONNECT requests**: only if `poolSharedMitmConnections=true`
- **For MITM requests (after CONNECT)**: always if `poolPerRequestInMitm=true`;
  otherwise the dedicated `serverConnectionsByHostAndPort` path is used
- **For plain HTTP requests**: always (no MITM/CONNECT gating applies)

### What happens during a CONNECT in mixed mode

```
CONNECT example.com:443
  → useSharedPool? (only if poolSharedMitmConnections=true)
  → Yes: pool.getOrCreateConnection()
    → Pool returns idle connection (TCP + TLS already up) OR creates new one
  → initializeConnectionFlow() with isReused check
    → Reused: skip ConnectChannel + EncryptChannel → RespondCONNECTSuccessful → MitmEncryptClientChannel
    → New: ConnectChannel → EncryptChannel → RespondCONNECTSuccessful → MitmEncryptClientChannel
  → After CONNECT flow completes:
    poolPerRequest? → releaseToPool() immediately
    !poolPerRequest? → pinned to client session (mitmPooled=true), released on disconnect
```

While the CONNECT is being established, plain HTTP requests to different hosts
continue to use the pool independently — the CONNECT flow does not block them.

### What happens during an HTTP GET in mixed mode (after CONNECT)

```
GET /api (through existing MITM tunnel)
  → useSharedPool?
    → poolPerRequest=true: pool.getOrCreateConnection() → borrows a (possibly different) connection
    → poolPerRequest=false: use dedicated serverConnectionsByHostAndPort entry
  → Response complete:
    → poolPerRequest=true: markResponseComplete() → releaseConnection()
    → poolPerRequest=false: connection stays ready for next request
```

### Pool sizing for mixed workloads

In mixed mode, the pool serves both plain HTTP requests and MITM upstream connections
from the same pool. Each MITM upstream TLS connection counts toward the per-host and
global limits. If you expect many concurrent MITM sessions to the same host, ensure
`maxConnectionsPerHost` is high enough to accommodate both HTTP and HTTPS demand.

Example: with `maxConnectionsPerHost=10`, if 8 HTTP requests and 5 MITM sessions all
target `api.example.com:443`, the 11th request will fail to acquire a connection.
Raise the limit or monitor `PoolMetrics` for borrow failures.

### Separate pool keys for MITM vs. plain HTTP

The pool key is computed by `ServerConnectionPool.computePoolKey()`:
- Plain HTTP to `example.com:443`: key = `"example.com:443:direct"`
- MITM CONNECT to `example.com:443`: key = `"example.com:443:direct"` (same key)

This means a plain HTTP request and a MITM upstream connection to the same
`host:port` compete for the same pool entries. This is intentional — they are
connections to the same server and should be limited together.

---

## Feature 1: `poolSharedMitmConnections` — Cross-Client Upstream Reuse

### What it does

When enabled, the upstream TLS connection created during a MITM `CONNECT` handshake is
stored in the shared connection pool (keyed by `host:port`). When a second (or third,
etc.) client connects to the **same** target host, the pool returns the cached
connection instead of opening a fresh TCP socket and TLS handshake.

### Configuration

```java
HttpProxyServer server = DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .withManInTheMiddle(myMitmManager)
    .withSharedServerConnectionPool(true)     // master switch
    .withPoolSharedMitmConnections(true)      // Phase 1
    .start();
```

Or via properties file:

```properties
use_shared_server_connection_pool=true
pool_shared_mitm_connections=true
```

### How it works

- The pool guard `!isMitming()` is replaced with a combined condition that checks the
  new flag (`poolSharedMitmConnections`).
- During the CONNECT flow in `ClientToProxyConnection.doReadHTTPInitial()`, when
  the flag is set, the connection is obtained via `pool.getOrCreateConnection()`
  instead of `ProxyToServerConnection.create()` + `serverConnectionsByHostAndPort.put()`.
- The existing `initializeConnectionFlow()` is extended: when the connection was
  retrieved from the pool and its channel is already active (`channel != null &&
  channel.isActive()`), the flow **skips** `ConnectChannel` and `EncryptChannel`
  — the TCP socket and TLS handshake are already done from a previous session.
- When the client disconnects, the server connection is released back to the pool
  (via `releaseToPool()`) instead of being closed, making it available for the next
  client.

### Key implementation details

- A new `isReused` boolean is computed at the top of `initializeConnectionFlow()`.
  When true, `ConnectChannel` and all chained/send-proxy/encrypt steps are bypassed.
- The `mitmPooled` flag on `ProxyToServerConnection` prevents `markResponseComplete()`
  from releasing the connection after each individual HTTP response — the connection
  stays pinned to the MITM session until the client disconnects.
- `ProxyToServerConnection` gains a `connectionPool` field (null for non-pooled
  connections), `createForPool()` static factory, `releaseToPool()`,
  `isManagedByPool()`, `isConnected()`, `isAvailableForNewRequest()`, and
  `getClientConnection()` — the latter routes responses to the correct client
  even when the `clientConnection` field points to the original client that
  created the connection.

### Use case

A browser opens 10 tabs to `https://api.example.com`. Each tab creates a separate
client TCP connection through the proxy. Without pooling, 10 upstream TLS sockets
are opened to `api.example.com:443`. With pooling, the first tab's upstream
connection is reused for tabs 2–10.

### Value

- Reduces upstream TLS handshake overhead (CPU, latency)
- Reduces server-side connection load
- Lowers the number of concurrent outbound sockets
- Most impactful for proxy deployments with many clients hitting the same origins

## Feature 2: `poolPerRequestInMitm` — Per-Request (vs. Per-Session) Borrowing

*Requires `poolSharedMitmConnections=true`.*

### What it does

Without this flag, the pooled connection is pinned to the client's MITM session for
its lifetime — it is released back to the pool only when the client disconnects.
With this flag, the connection is released back to the pool **after each individual
HTTP request** completes, even while the client TCP session remains open. Subsequent
HTTP requests through the same MITM tunnel acquire a (possibly different) connection
from the pool.

### Configuration

```java
HttpProxyServer server = DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .withManInTheMiddle(myMitmManager)
    .withSharedServerConnectionPool(true)     // master switch
    .withPoolSharedMitmConnections(true)      // Phase 1
    .withPoolPerRequestInMitm(true)           // Phase 2
    .start();
```

Or via properties file:

```properties
use_shared_server_connection_pool=true
pool_shared_mitm_connections=true
pool_per_request_in_mitm=true
```

### How it works

- The `useSharedPool` condition in `ClientToProxyConnection.doReadHTTPInitial()`
  is extended: when `poolPerRequestInMitm` is true, MITM requests (after the
  CONNECT) go through the pool path.
- The CONNECT response flow sets `releaseToPoolOnConnectComplete = true` on the
  server connection. After the CONNECT flow completes (in
  `connectionSucceeded()`), the connection is immediately released to the pool.
- `serverConnectionsByHostAndPort` is NOT used for MITM connections when this
  flag is set — every HTTP request goes through `pool.getOrCreateConnection()`.
- `markResponseComplete()` calls `connectionPool.releaseConnection(this)` for
  per-request connections (ones where `mitmPooled` is false). This returns the
  connection to the available queue for another client's request.
- HTTP pipelining is handled via `PendingRequest` tracking in the pool.
- The `disconnected()` method in `ClientToProxyConnection` avoids a double-release:
  per-request connections are already in the pool by the time the client
  disconnects, so they just need `removeConnection()` on disconnect, not
  `releaseToPool()`.

### Key implementation details

- The `releaseToPoolOnConnectComplete` transient flag on
  `ProxyToServerConnection` is set during `initializeConnectionFlow()` and
  consumed once in `connectionSucceeded()`. After release, subsequent HTTP
  requests from the same MITM tunnel go through `doReadHTTPInitial()` → pool
  path.
- `setCurrentClientConnectionForRequest()` is called on the borrowed connection
  to ensure responses are routed to the correct client.
- A critical fix was made during development: `MitmEncryptClientChannel.execute()`
  used the constructor field `clientConnection` (the original client that
  initiated the CONNECT) instead of `getClientConnection()` (the client that
  is making the current request). This caused
  `testMultipleRequestsOverHTTPS` to fail with an SSL handshake error because
  the encrypt was applied to the wrong channel. The fix was to use
  `getClientConnection()` consistently.

### Use case

A client opens a single HTTPS connection and sends a GET, then sits idle for
30 seconds, then sends a POST. Without per-request pooling, the upstream
connection is held idle the whole time. With per-request pooling, it is
returned to the pool after the GET, available for other clients, and
re-acquired for the POST.

### Value

- Better connection utilization — idle time during a client session is
  reclaimed for other clients
- Enables a smaller connection pool to serve the same workload
- Connections are not held captive by idle client sessions

## Combined Scenario

With both flags enabled, upstream connections are pooled across clients **and**
released between requests within a single client session. This is the most
aggressive connection-sharing mode, providing the highest potential connection
reuse.

## Pool Metrics

The `ServerConnectionPool` interface exposes `getMetrics()`, returning a
`PoolMetrics` object with:

| Metric                 | Description                              |
|------------------------|------------------------------------------|
| `getTotalConnections()` | Total connections in the pool            |
| `getActiveConnections()`| Connections currently borrowed           |
| `getIdleConnections()`  | Connections available for reuse          |
| `getBorrowCount()`      | Cumulative borrow operations             |
| `getReturnCount()`      | Cumulative return operations             |
| `getEvictionCount()`    | Cumulative eviction operations           |
| `getValidationFailureCount()` | Connections that failed validation |

These metrics are accessible from tests or monitoring code by casting
`HttpProxyServer` to `DefaultHttpProxyServer` and calling
`getServerConnectionPool().getMetrics()`.

## Feature Interaction Matrix

| `useSharedPool` | `poolSharedMitm` | `poolPerRequest` | Behavior |
|---|---|---|---|
| `false` | — | — | Legacy: dedicated connection per client per host. All connections are tracked in `serverConnectionsByHostAndPort` and closed on disconnect. Main-branch behavior. |
| `true`  | `false` | `false` | Plain HTTP is pooled. CONNECT and MITM use dedicated connections (main-branch pool behavior). |
| `true`  | `true`  | `false` | Plain HTTP **and** MITM upstream connections are pooled across client sessions. Each MITM session holds one pooled connection until the client disconnects. |
| `true`  | `true`  | `true`  | Full pooling: plain HTTP and MITM connections are borrowed per request and released back to the pool between requests within the same client session. |

(`poolPerRequestInMitm=true` without `poolSharedMitmConnections=true` has no
effect — per-request mode requires the shared pool for MITM.)

## Configuration Reference

### Builder methods on `HttpProxyServerBootstrap`

```java
// Base pooling (PR #724 infrastructure)
.withSharedServerConnectionPool(boolean)
.withServerConnectionPoolType(ServerConnectionPoolType)
.withMaxConnectionsPerHost(int)
.withMaxConnections(int)
.withPoolIdleTimeout(Duration)

// MITM-specific (this branch)
.withPoolSharedMitmConnections(boolean)      // default false
.withPoolPerRequestInMitm(boolean)            // default false
```

### Properties file keys (for `--config`)

```properties
# Base pooling
use_shared_server_connection_pool=true
server_connection_pool_type=COMMONS_POOL2|CONCURRENT_MAP|STORMPOT
max_connections_per_host=10
max_total_connections=200

# MITM-specific
pool_shared_mitm_connections=true
pool_per_request_in_mitm=true
```

## Test Coverage

### Unit tests

| Test class | Tests added | What it covers |
|---|---|---|
| `ServerConnectionPoolConfigTest` | 8 | Default values, fluent setters/getters, independence from `enabled` flag |
| `DefaultHttpProxyServerBootstrapTest` | 5 | Property parsing for both flags via `Properties` constructor |

### Integration tests

| Test class | Tag | Tests | What it covers |
|---|---|---|---|
| `MitmWithSharedPoolTest` | — | 4 | GET, POST, cross-client reuse, pool metrics (borrow count) |
| `MitmWithPerRequestPoolTest` | `slow-test` | 4 | GET, POST, sequential reuse, cross-client reuse |

### Existing tests exercising the underlying pool infrastructure

| Test class | Tests | What it covers |
|---|---|---|
| `ConcurrentMapServerConnectionPoolTest` | 24 | Pool implementation: borrow, release, eviction, pending requests |
| `StormpotServerConnectionPoolTest` | 8 | Stormpot pool implementation |
| `SharedConnectionPoolTest` | 13 | Integrated shared pool for plain HTTP |
| `ServerConnectionPoolTypeTest` | 6 | Pool type selection across all three implementations |
| `ClientToProxyConnectionShortCircuitTest` | 5 | Short-circuit filter response with pooled connections |
| `ClientToProxyConnectionBackpressureTest` | 15 | Backpressure / saturation with pooled connections |

## Excluded Protocols

The following traffic types are never pooled and always use dedicated
`ProxyToServerConnection` instances tracked in `serverConnectionsByHostAndPort`:

| Protocol | Reason |
|---|---|
| **WebSocket** (`Upgrade: websocket`) | After the HTTP upgrade handshake, the HTTP codecs are replaced with `WebSocketFramePipeHandler`. The connection becomes a raw frame pipe between client and server with no HTTP request/response lifecycle to trigger pool release. |
| **Tunneling** (non-MITM CONNECT, i.e. regular HTTPS) | Once the CONNECT response is sent, the connection enters raw TCP tunneling mode. There are no HTTP request/response boundaries — all data flows bidirectionally until the tunnel closes, so the connection cannot be returned to the pool. This is the default HTTPS proxy behavior (no intercept), and it is **never** pooled. Only MITM-intercepting HTTPS can be pooled (behind `poolSharedMitmConnections`). |
| **Switching Protocols** (other `Upgrade` headers) | Same as WebSocket — HTTP codecs are removed and the connection switches to a different protocol, making pool return impossible. |

The `useSharedPool` condition explicitly checks `!isTunneling()` and
`!ProxyUtils.isSwitchingToWebSocketProtocol(httpRequest)` before every request.
No configuration flag can override these exclusions.

## Known Limitations

- **Metrics API not on `HttpProxyServer` interface:** To access pool metrics
  from client code, cast to `DefaultHttpProxyServer`. This is a minor API gap.
- **No `encryptForMitm()` integration test:** The code path reached via
  `disableSslForNonTls` (retry after failed TLS to a plain-text server) has
  no integration test coverage. A bug in `encryptForMitm()` analogous to the
  `MitmEncryptClientChannel` bug was fixed during development.
