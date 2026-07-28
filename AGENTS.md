# LittleProxy Agent Guidelines

## Project

LittleProxy is a high-performance HTTP/HTTPS proxy library written on top of Netty.
It is consumed as an embedded library (`io.github.littleproxy:littleproxy`) and can also run as a standalone
executable via the shaded jar (`Launcher` main class).

The active fork is maintained at https://github.com/LittleProxy/LittleProxy.

## Build & Test Commands

```bash
# Compile
mvn compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=ActivityLoggerTest

# Run a single test method
mvn test -Dtest=ActivityLoggerTest#testResponseSentToClient

# Run smoke tests (excludes slow tests)
mvn test -Psmoke-test

# Run only slow tests
mvn test -Pslow-tests

# Package (produces the shaded runnable jar)
mvn clean package

# Skip tests while packaging
mvn clean package -DskipTests

# Clean build
mvn clean compile

# Run the shaded jar locally
./run.bash --server --config ./config/littleproxy.properties
```

CI (`.github/workflows/main.yml`) runs `-Psmoke-test` then `-Pslow-tests` on Ubuntu/macOS/Windows with JDK 17.

## Toolchain Constraints

- **Main source targets Java 11** (`<release>11</release>`); **tests compile with Java 17** (`compile-tests-with-java17` execution). New main-source code must not use language features newer than Java 11.
- Source is auto-formatted by **Spotless (google-java-format 1.28.0)** in the `compile` phase — it rewrites files on every build. Don't hand-align imports; just run the build.
- **ErrorProne** runs during compilation with `-Xep:MissingSummary:OFF -Xep:JdkObsolete:OFF -Xep:ReferenceEquality:OFF -Xep:OperatorPrecedence:OFF`. Other checks are active.
- `maven-enforcer-plugin` bans `junit:junit` and `org.hamcrest:hamcrest-core` — use JUnit Jupiter + AssertJ.

## Code Style & Formatting

This project uses **Spotless** with **Google Java Format**:

```bash
# Check formatting
mvn spotless:check

# Apply formatting
mvn spotless:apply
```

### Formatting Rules
- **Indent**: 2 spaces (no tabs)
- **Line length**: 100 characters
- **Braces**: K&R style (opening brace on same line)
- **Imports**: Ordered and unused imports removed automatically

### Import Order
1. `java.*` imports
2. `javax.*` imports
3. Third-party libraries (alphabetically)
4. `org.littleshoot.proxy.*` imports
5. Static imports

## Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Classes | PascalCase | `ActivityLogger`, `FlowContext` |
| Interfaces | PascalCase (no I-prefix) | `ActivityTracker`, `LogEntryFormatter` |
| Methods | camelCase | `requestReceivedFromClient()`, `getTimingData()` |
| Variables | camelCase | `flowContext`, `timedRequest` |
| Constants | UPPER_SNAKE_CASE | `UTC`, `LOG` |
| Enums | PascalCase | `LogFormat`, `StandardField` |
| Packages | lowercase | `org.littleshoot.proxy.extras.logging` |

## Type Guidelines

- Use `var` for local variables when type is obvious
- Prefer `final` for fields and parameters when possible
- Use `Optional<T>` for nullable return values
- Use primitive types over boxed types when possible
- Use `Long` for timing values (milliseconds)

## Error Handling

- Use `LOG.error()` with context for unexpected errors
- Use `LOG.warn()` for recoverable issues
- Use `LOG.debug()` for diagnostic information
- Always include `flowId` in log messages for correlation
- Prefer early returns to reduce nesting
- Use specific exception types over generic `Exception`

## Javadoc

- Required for public classes and methods
- Use `@param`, `@return`, `@throws` tags
- Include usage examples for complex methods
- Keep first sentence concise (summary line)

## Testing

- Use JUnit 5 with AssertJ assertions
- Test class naming: `ClassNameTest`
- Test method naming: use descriptive names without the `test` prefix (JUnit 3 legacy). Since JUnit 4+, `@Test` annotations make naming conventions unnecessary. Examples: `filtering()`, `methodUnderTest_condition_expectedResult`, or `shouldFilterOnError`. Note: `#` is not a valid Java identifier — do not use it in method names
- Use `@Tag("slow-test")` for long-running tests
- Mock external dependencies with Mockito
- Tests are JUnit Jupiter + AssertJ + Mockito; Jetty and WireMock are used as real backends (do not mock them away).
- Long-running or timing-sensitive tests are marked `@Tag("slow-test")` and excluded from the default/smoke profile.
- Integration tests start real proxy instances on ephemeral ports; prefer extending `AbstractProxyTest` / `BaseProxyTest` / `BaseChainedProxyTest` rather than duplicating setup.
- Test resources include keystores and `log4j.xml` under `src/test/resources/`.
- Always use SLF4J (`org.slf4j.Logger`/`LoggerFactory`) for logging, not log4j directly — this is the logging API used throughout the codebase.

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

## Architecture Patterns

- **Strategy Pattern**: For formatters (LogEntryFormatter)
- **Builder Pattern**: For configuration (LogFieldConfiguration)
- **Adapter Pattern**: ActivityTrackerAdapter for optional overrides
- Store timing data in FlowContext, not as parameters
- Use ConcurrentHashMap for thread-safe collections

## Common Pitfalls

- Don't use `System.out.println()` - use SLF4J Logger
- Don't catch generic exceptions without logging
- Don't forget to call `super()` in overridden lifecycle methods
- Don't use blocking operations in Netty event loops

## Release

Release steps (version bumps in `pom.xml` + `README.md`, `deploy.bash`, tag, publish on Sonatype Central) are documented in `CONTRIBUTING.md`. Do not bump versions unless a release is being cut.
