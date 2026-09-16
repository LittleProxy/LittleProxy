# LittleProxy

A high-performance HTTP/HTTPS proxy library on Netty, consumed as an embedded library and runnable as a standalone shaded jar.

## Language

**ServerConnectionPool**:
The contract for pooling `ProxyToServerConnection` instances. It is also the ServiceLoader SPI: implementations are discovered at startup, selected by name, and configured once through `initialize(ServerConnectionPoolContext)`.
_Avoid_: pool factory, pool provider

**Pool implementation name**:
The string an implementation returns from `getName()`, used to select it (`concurrent_map`, or a custom name from an implementation jar). Matching is case-insensitive, so the lowercase properties spelling resolves however the implementation capitalizes its name; two names differing only in case are ambiguous.
_Avoid_: pool type, pool category

**ServerConnectionPoolContext**:
The object carried at initialization: the `HttpProxyServer`, the optional `GlobalTrafficShapingHandler`, and an option map of standard and implementation-specific settings.
_Avoid_: pool config carrier (implementation detail), pool environment

**Option key**:
A single key inside the context option map. Standard keys (`maxConnectionsPerHost`, `maxTotalConnections`, `poolIdleTimeout`) are typed by the bootstrap; implementation-specific keys are interpreted by the implementation itself. From a properties file, options scoped to a pool are declared as `server_connection_pool.<poolName>.<option>` and handed over as raw strings; their snake_case suffix is converted to the camelCase option key (relaxed binding — already-camelCase keys pass through), and programmatically they are added with `withServerConnectionPoolOption(...)` (no conversion).
_Avoid_: pool property (reserved for the properties file), pool setting

**ServerConnectionPoolLoader**:
The component that discovers implementations via `ServiceLoader`, resolves the configured name (case-insensitively), and falls back to `concurrent_map` when the name is unknown or nothing is registered.
_Avoid_: pool registry, pool resolver

**concurrent_map**:
The built-in, default pool implementation (`ConcurrentMapServerConnectionPool`), always registered in the littleproxy jar.
_Avoid_: the default pool (ambiguous), ConcurrentMap pool (implementation detail)