# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LittleProxy is a high-performance HTTP/HTTPS proxy library written on top of Netty. 
It is consumed as an embedded library (`io.github.littleproxy:littleproxy`) and can also run as a standalone 
executable via the shaded jar (`Launcher` main class).

The active fork is maintained at https://github.com/LittleProxy/LittleProxy.

## Build & Test Commands

- Build & run all tests: `mvn test`
- Package (produces the shaded runnable jar): `mvn clean package`
- Skip tests while packaging: `mvn clean package -DskipTests`
- Smoke tests only (fast subset, excludes `slow-test` tag): `mvn package -Psmoke-test`
- Slow tests only (tagged `@Tag("slow-test")`): `mvn package -Pslow-tests`
- Single test class: `mvn test -Dtest=MitmProxyTest`
- Single test method: `mvn test -Dtest=MitmProxyTest#testProxyConnects`
- Run the shaded jar locally: `./run.bash --server --config ./config/littleproxy.properties` (wraps `mvn package -Dmaven.test.skip=true` + `java -jar`)

CI (`.github/workflows/main.yml`) runs `-Psmoke-test` then `-Pslow-tests` on Ubuntu/macOS/Windows with JDK 17.

## Toolchain Constraints

- **Main source targets Java 11** (`<release>11</release>`); **tests compile with Java 17** (`compile-tests-with-java17` execution). New main-source code must not use language features newer than Java 11.
- Source is auto-formatted by **Spotless (google-java-format 1.28.0)** in the `compile` phase — it rewrites files on every build. Don't hand-align imports; just run the build.
- **ErrorProne** runs during compilation with `-Xep:MissingSummary:OFF -Xep:JdkObsolete:OFF -Xep:ReferenceEquality:OFF -Xep:OperatorPrecedence:OFF`. Other checks are active.
- `maven-enforcer-plugin` bans `junit:junit` and `org.hamcrest:hamcrest-core` — use JUnit Jupiter + AssertJ.

## High-Level Architecture

The proxy is built around two mirror Netty channel handlers and a small state machine.

- **`DefaultHttpProxyServer`** (`impl/`) — bootstrap/entry point; owns the `ServerGroup` (shared Netty event loops), `HttpFiltersSource`, optional `MitmManager`, `ChainedProxyManager`, and `ProxyAuthenticator`. `HttpProxyServerBootstrap` is the fluent builder users interact with.
- **`ClientToProxyConnection`** (`impl/`) — one per inbound client channel. Decodes HTTP requests, runs the filter chain, resolves/reuses a per-(host,port) `ProxyToServerConnection`, and writes responses back to the client. Also handles `CONNECT` (tunnel vs. MITM branch) and proxy auth.
- **`ProxyToServerConnection`** (`impl/`) — one per upstream target. Drives `ConnectionFlow` during setup, then proxies request/response chunks. Caches chained-proxy fallback state.
- **`ConnectionFlow` / `ConnectionFlowStep`** (`impl/`) — ordered async step machine used to set up outbound connections: `ConnectChannel` → optional chained-proxy handshake (HTTP CONNECT / SOCKS4 / SOCKS5) → optional `StartTunneling` or `EncryptChannel` (MITM) → `RespondCONNECTSuccessful` → `MitmEncryptClientChannel`. Each step returns a Netty `Future` and advances via callbacks.
- **`ProxyConnection`** (`impl/`) — abstract base for both connections; owns the `ConnectionState` transitions (`AWAITING_INITIAL`, `AWAITING_CHUNK`, `CONNECTING`, `HANDSHAKING`, `NEGOTIATING_CONNECT`, `AWAITING_PROXY_AUTHENTICATION`, `DISCONNECT_REQUESTED`, `DISCONNECTED`). Saturation callbacks implement backpressure (pause client reads when any upstream is saturated, and vice versa).
- **`HttpFilters` / `HttpFiltersSource`** (top-level package) — primary extension point. A new `HttpFilters` instance is created per request via `filterRequest(...)`. Callback order (request → server connect → response) is documented in `LittleProxy_Request_Handling_Architecture.md`; returning a non-null `HttpResponse` from `clientToProxyRequest`/`proxyToServerRequest` short-circuits the request.
- **`MitmManager` + `SslEngineSource`** — supply `SSLEngine`s for HTTPS interception. The in-tree `extras/SelfSignedMitmManager` is demo-grade; production setups typically plug in `LittleProxy-mitm` or the BrowserMob `mitm` module (see README).
- **`ChainedProxyManager` / `ChainedProxy`** — per-request upstream proxy selection. Returning `ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION` or an empty queue fails over to a direct connection; otherwise connection failures walk the queue.
- **Netty pipelines** — client-side: `HAProxyMessageDecoder?` → `HttpRequestDecoder` → optional `HttpObjectAggregator` → monitors → `IdleStateHandler` → `ClientToProxyConnection`. Server-side mirrors this with `HttpRequestEncoder` / `HeadAwareHttpResponseDecoder` plus optional `GlobalTrafficShapingHandler` for throttling. When a `CONNECT` tunnel is established (without MITM), HTTP codecs are removed and data flows as raw bytes via `readRaw`/`write`.
- **`extras/`** contains production-adjacent but optional implementations (`ActivityLogger`, `LogFormat`, `SelfSignedMitmManager`, `HAProxyMessageEncoder`, `TrustingTrustManager`). Core must not depend on `extras`.

For diagrams and the full lifecycle of CONNECT/MITM/filter callbacks, see `LittleProxy_Request_Handling_Architecture.md`.

## Testing Notes

- Tests are JUnit Jupiter + AssertJ + Mockito; Jetty and WireMock are used as real backends (do not mock them away).
- Long-running or timing-sensitive tests are marked `@Tag("slow-test")` and excluded from the default/smoke profile.
- Integration tests start real proxy instances on ephemeral ports; prefer extending `AbstractProxyTest` / `BaseProxyTest` / `BaseChainedProxyTest` rather than duplicating setup.
- Test resources include keystores and `log4j.xml` under `src/test/resources/`.

## Release

Release steps (version bumps in `pom.xml` + `README.md`, `deploy.bash`, tag, publish on Sonatype Central) are documented in `CONTRIBUTING.md`. Do not bump versions unless a release is being cut.
