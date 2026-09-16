# LittleProxy

A high-performance HTTP/HTTPS proxy library on Netty, consumed as an embedded library and runnable as a standalone shaded jar.

## Language

**ServerConnectionPool**:
The contract for pooling `ProxyToServerConnection` instances. It is also the ServiceLoader SPI: implementations are discovered at startup, selected by name, and configured once through `initialize(ServerConnectionPoolContext)`.
_Avoid_: pool factory, pool provider

**Pool implementation name**:
The unique string an implementation returns from `getName()`, used to select it (`CONCURRENT_MAP`, or a custom name from an implementation jar).
_Avoid_: pool type, pool category

**ServerConnectionPoolContext**:
The object carried at initialization: the `HttpProxyServer`, the optional `GlobalTrafficShapingHandler`, and an option map of standard and implementation-specific settings.
_Avoid_: pool config carrier (implementation detail), pool environment

**Option key**:
A single key inside the context option map. Standard keys (`maxConnectionsPerHost`, `maxConnections`, `idleTimeout`) are typed by the bootstrap; implementation-specific keys are interpreted by the implementation itself. From a properties file, options scoped to a pool are declared as `server_connection_pool.<poolName>.<option>` and handed over as raw strings; programmatically they are added with `withServerConnectionPoolOption(...)`.
_Avoid_: pool property (reserved for the properties file), pool setting

**ServerConnectionPoolLoader**:
The component that discovers implementations via `ServiceLoader`, resolves the configured name, and falls back to `CONCURRENT_MAP` when the name is unknown or nothing is registered.
_Avoid_: pool registry, pool resolver

**CONCURRENT_MAP**:
The built-in, default pool implementation (`ConcurrentMapServerConnectionPool`), always registered in the littleproxy jar.
_Avoid_: the default pool (ambiguous), ConcurrentMap pool (implementation detail)