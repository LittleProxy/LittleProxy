Pull Request: Enhanced Activity Tracking and Advanced Logging Features
Summary
This PR introduces these features :
- ULID-based flow IDs for globally unique, sortable request tracing
- Lifecycle event tracking for ActivityTracker with FlowContext
- Three-tier logging strategy in ActivityLogger for structured metrics
- Advanced field configuration for flexible HTTP access logging
- Standards-compliant log formats (W3C, Squid, HAProxy)
- KEYVALUE format with INFO-level format selection
- Performance optimizations for production environments
- Unified log formatting across all log levels (INFO, DEBUG, TRACE)
---
Summary of Recent Changes (Post-PR Updates)
- **ActivityTracker Interface Enhancement**: Added `clientSSLHandshakeStarted(FlowContext)` method for accurate SSL handshake timing measurement
- **Timing Mode Full Implementation**: `--activity_timing_mode` CLI option now fully functional with proper field filtering
- **Timing Field Fixes**: All timing metrics now properly populated (ssl_handshake_time_ms, tcp_connection_establishment_time_ms, tcp_client_connection_duration_ms, tcp_server_connection_duration_ms)
- **Timing Data Centralization**: All timing data consolidated in FlowContext timingData map, removed duplicate storage in ClientState/ServerState
- **NPE Prevention**: Fixed null pointer exceptions when timing data is not yet available in lifecycle events
- **Test Coverage**: Increased from 36 to 50 comprehensive tests for ActivityLogger
- **Lifecycle Timing Exposure** (new): lifecycle events now include timing deltas (client/server connection ages, request spacing, server connect latency) and reuse a single FlowContext instance across connect/disconnect to preserve values.
- **Null-safe Client Connect Logging** (new): `CLIENT_CONNECTED` no longer throws when the client address is temporarily unavailable; regression test added.
- **DNS Timing Metrics** (new): DNS resolution start/end/duration now tracked in FlowContext and surfaced in lifecycle logs (e.g., `server_connected`) as `dns_resolution_time_ms`.
- **Request/Response Header Logging** (new): CLI options now differentiate between request (`--activity_log_request_*`) and response (`--activity_log_response_*`) header capture. Logged header fields are automatically prefixed with `req_` or `res_` to indicate their origin.
- **Response Time Categorization** (new): Configurable response time thresholds with `--activity_log_response_time_thresholds` CLI option. Categories requests as fast/medium/slow/very_slow based on customizable millisecond thresholds (default: 100,500,2000).
- **Latency and Transfer Time Metrics** (new): Added `response_latency_ms` (Time to First Byte - TTFB) and `response_transfer_time_ms` for detailed performance breakdown. Shows the relationship: `response_time = latency + transfer_time`.
- **Comprehensive Test Coverage** (new): Expanded from 50 to 589+ tests covering all logging components, formatters, field extractors, configuration classes, and adapter patterns. 46 test files with complete coverage of logging infrastructure.

---
Changes Overview
1. ULID-Based Flow IDs
   Changed from AtomicLong to ULID (Universally Unique Lexicographically Sortable Identifier)
   | Before | After |
   |--------|-------|
   | flow-1, flow-2, flow-123 | 01KGNMFEFZ84ZAR511NTRAFW13 |
   Benefits:
- Globally unique across multiple LittleProxy instances
- Lexicographically sortable by generation time
- URL-safe (Crockford's base32 encoding)
- No coordination needed between instances
  Dependencies: Added ulid-creator library (v5.2.3)
---
2. ActivityTracker Interface Enhancement
   New Lifecycle Methods (for test run detection):
   | Method | Description |
   |--------|-------------|
   | clientSSLHandshakeStarted(FlowContext) | SSL handshake started (for timing measurement) |
   | clientSSLHandshakeSucceeded(FlowContext, SSLSession) | SSL handshake completed |
   | serverConnected(FullFlowContext, InetSocketAddress) | Server connection established |
   | serverDisconnected(FullFlowContext, InetSocketAddress) | Server connection closed |
   | connectionSaturated(FlowContext) | Connection became non-writable |
   | connectionWritable(FlowContext) | Connection became writable again |
   | connectionTimedOut(FlowContext) | Idle timeout triggered |
   | connectionExceptionCaught(FlowContext, Throwable) | Exception caught on connection |
   Changed Methods (now accept FlowContext):
   | Before | After |
   |--------|-------|
   | clientConnected(InetSocketAddress) | clientConnected(FlowContext) |
   | clientSSLHandshakeSucceeded(InetSocketAddress, SSLSession) | clientSSLHandshakeSucceeded(FlowContext, SSLSession) |
   | clientDisconnected(InetSocketAddress, SSLSession) | clientDisconnected(FlowContext, SSLSession) |
   Implementation:
- ActivityTrackerAdapter: No-op implementations for backward compatibility
- ClientToProxyConnection: Client-side lifecycle notifications with FlowContext
- ProxyToServerConnection: Server-side lifecycle notifications
---
3. Duration Field Renames
    Clarified duration metrics naming:
    | Old Name | New Name | Description |
    |----------|----------|-------------|
    | duration_ms | http_request_processing_time_ms | HTTP request processing time (receipt to response) |
    | client_connection_duration_ms | tcp_client_connection_duration_ms | Total TCP connection lifetime |
    | server_connect_time | tcp_connection_establishment_time_ms | Time to establish TCP connection |
    | server_connection_duration | tcp_server_connection_duration_ms | Total server TCP connection duration |
    | ssl_handshake_time | ssl_handshake_time_ms | SSL/TLS handshake duration |
   Rationale: The new names make the timing relationship clear - TCP connection lifetime ≥ HTTP request time.
---
3a. Timing Data Architecture Refactor
   Major architectural change: All timing data now stored in FlowContext instead of passing as parameters.
   | Aspect | Before | After |
   |--------|--------|-------|
   | Storage | Separate duration parameter | FlowContext timingData map |
   | LogField.extractValue() | extractValue(FlowContext, HttpRequest, HttpResponse, long duration) | extractValue(FlowContext, HttpRequest, HttpResponse) |
   | LogEntryFormatter.format() | format(..., long durationMs, ...) | format(..., FlowContext, ...) |
   | Timing Access | Duration parameter only | FlowContext.getTimingData(key) for all metrics |
Timing Data Keys (FlowContext):
    - http_request_processing_time_ms - HTTP request processing duration
    - tcp_connection_establishment_time_ms - TCP connection setup time
    - tcp_client_connection_duration_ms - Total client TCP connection lifetime
    - tcp_server_connection_duration_ms - Total server TCP connection duration
    - ssl_handshake_time_ms - SSL/TLS handshake duration
   Benefits:
   - Centralized timing data storage
   - All timing metrics accessible from FlowContext
   - Cleaner API without multiple timing parameters
   - Extensible for future timing metrics
---
4. Flow ID in All Logs
   All TRACE/DEBUG logs now include flow ID for correlation:
   [01KGNMFEFZ84ZAR511NTRAFW13] ENTER clientConnected - address=/127.0.0.1:12345, thread=main, timestamp=...
   [01KGNMFEFZ84ZAR511NTRAFW13] Client connected: /127.0.0.1:12345
   [01KGNMFEFZ84ZAR511NTRAFW13] ENTER clientSSLHandshakeSucceeded - address=/127.0.0.1:12345, protocol=TLSv1.2, ...
   [01KGNMFEFZ84ZAR511NTRAFW13] Client SSL handshake succeeded: /127.0.0.1:12345, duration: 15ms
   Implementation:
- Added flowId field to ClientState class
- Flow ID generated at connection time in clientConnected()
- All lifecycle methods retrieve flowId from state and prefix logs with [flowId]
---
5. ActivityLogger Three-Tier Logging Strategy
   | Log Level | Purpose | Example Output |
   |-----------|---------|----------------|
   | TRACE | Detailed diagnostics | [flowId] ENTER method - address=..., thread=..., timestamp=... |
   | DEBUG | Essential operations | [flowId] Client connected: ... |
   | INFO  | Structured summary | Format-dependent (KEYVALUE, JSON, etc.) |
   INFO Level Format Selection:
   The configured format is now used for INFO level logging:
- --activity_log_format JSON → INFO outputs JSON
- --activity_log_format KEYVALUE → INFO outputs key-value pairs
- --activity_log_format CLF → INFO outputs CLF format
---
6. KEYVALUE Format
   New structured text format (original INFO format):
   --activity_log_format KEYVALUE
   Output:
   flow_id=01KGNMFEFZ84ZAR511NTRAFW13 client_ip=127.0.0.1 client_port=53326 server_ip=172.217.20.36 server_port=443 method=CONNECT uri="www.google.com:443" protocol=HTTP/1.1 status=200 bytes=- http_request_ms=4 tcp_connection_ms=5 server_connect_ms=0 ssl_handshake_ms=0 client_saturations=0 server_saturations=0 exception=none
   Benefits:
- Human-readable
- Easy to grep/parse
- Includes all metrics
---
7. Standards-Compliant Log Formats
   Fixed non-compliant formats:
   | Format | Status | Fix |
   |--------|--------|-----|
   | W3C | ✅ Fixed | cs-uri-stem now contains only path (not full URL) |
   | Squid | ✅ Fixed | Timestamp calculation corrected, dynamic cache result (TCP_HIT/TCP_MISS) |
   | HAProxy | ✅ Fixed | Added full format with process name, timing buckets, counters |
   Format Compliance:
- CLF: Apache/NCSA Common Log Format (unchanged, already compliant)
- ELF: NCSA Extended/Combined Log Format (unchanged, already compliant)
- W3C: W3C Extended Log File Format (now compliant)
- Squid: Squid native log format (now compliant)
- HAProxy: HAProxy HTTP log format (now compliant)
- JSON/LTSV/CSV: Flexible formats with flow_id support
---
8. FLOW_ID Standard Field
   Added FLOW_ID to StandardField enum for formatted logs:
```java
public enum StandardField implements LogField {
  FLOW_ID("flow_id", "Unique flow identifier for tracing"),
  // ... other fields
}
```
Available in: JSON, LTSV, CSV formats
---
9. Advanced Field Configuration
   New Classes (org.littleshoot.proxy.extras.logging):
- LogFieldConfiguration: Builder pattern for field configuration
- StandardField: Enum of standard fields (e.g., TIMESTAMP, CLIENT_IP, FLOW_ID)
- ComputedField: Derived fields (e.g., GEOLOCATION_COUNTRY)
- PrefixRequestHeaderField/RegexRequestHeaderField: Header matching by prefix/regex
- ExcludeRequestHeaderField: Exclude sensitive headers (e.g., Authorization)
- Pre-configured field sets: SecurityMonitoringConfig, PerformanceAnalyticsConfig, APIManagementConfig
  Key Features:
- Log headers by prefix (X-Custom-*) or regex (X-.*-Id)
- Mask sensitive data (e.g., Authorization, Cookie)
- Transform field names (e.g., lower_underscore)
---
10. CLI Enhancements
    New Options:
    --activity_log_level TRACE|DEBUG|INFO|WARN|ERROR|OFF  # Set log level
    --activity_log_field_config <path>                    # JSON configuration file
    --activity_log_prefix_headers "X-Custom-,X-Trace-"    # Include headers by prefix
    --activity_log_regex_headers "X-.*-Id"                # Include headers by regex
    --activity_log_exclude_headers "Authorization,Cookie" # Exclude sensitive headers
    --activity_log_mask_sensitive true                    # Mask sensitive values
    --activity_timing_mode OFF|MINIMAL|ALL                # Timing field configuration (default: MINIMAL)
    Timing Mode Options:
    | Mode | Timing Fields Included | Use Case |
    |------|------------------------|----------|
    | OFF | None | Disable timing metrics |
    | MINIMAL | http_request_processing_time_ms only | Production (default) |
    | ALL | All timing fields | Debug/performance analysis |
    Example Usage:
    ```bash
    # Minimal timing (default) - only HTTP request processing time
    ./run.bash --activity_timing_mode MINIMAL
    
    # All timing metrics for detailed analysis
    ./run.bash --activity_timing_mode ALL
    
    # Disable timing completely
    ./run.bash --activity_timing_mode OFF
    ```
---
11. Log4j2 Configuration Updates
- System property support: ${sys:log4j2.ActivityLogger.level:-INFO}
- Fixed logger name to org.littleshoot.proxy.extras.logging.ActivityLogger
- Both sync and async configurations updated
---
12. New StandardField Entries
 - FLOW_ID - Unique flow identifier for tracing
 - HTTP_REQUEST_PROCESSING_TIME_MS - HTTP request processing duration (renamed from duration)
 - TCP_CONNECTION_ESTABLISHMENT_TIME_MS - Time to establish TCP connection (renamed from server_connect_time)
 - TCP_CLIENT_CONNECTION_DURATION_MS - Total client TCP connection lifetime (renamed from client_connection_duration)
 - TCP_SERVER_CONNECTION_DURATION_MS - Total server TCP connection duration (renamed from server_connection_duration)
 - SSL_HANDSHAKE_TIME_MS - SSL/TLS handshake duration
- SATURATION_COUNT - Number of saturation events
- EXCEPTION_TYPE - Exception class name
- RESPONSE_TIME_CATEGORY - Categorizes response time as fast/medium/slow
---
13. Documentation
- Comprehensive updates to PERFORMANCE_AND_LOGGING.md
- Three-tier logging strategy explanation
- Field configuration examples
- CLI usage examples
- Performance impact analysis
- Format compliance documentation
---
14. Testing
- 589+ comprehensive tests across 46 test files (increased from 50)
- ActivityLogger testing including lifecycle events and SSL handshake timing
- All 21 StandardField extractors tested
- All 10 LogEntryFormatter implementations tested (CLF, ELF, W3C, JSON, LTSV, CSV, Squid, HAProxy, KEYVALUE)
- Header field testing: RequestHeaderField, ResponseHeaderField, PrefixRequestHeaderField, PrefixResponseHeaderField, RegexRequestHeaderField, RegexResponseHeaderField, ExcludeRequestHeaderField, ExcludeResponseHeaderField
- Configuration testing: LogFieldConfigurationFactory, LoggingConfiguration, LogEntryFormatterFactory
- Preset configuration testing: SecurityMonitoringConfig, PerformanceAnalyticsConfig, APIManagementConfig
- ResponseTimeCategoryField testing with configurable thresholds
- Adapter pattern testing: ActivityTrackerAdapter, HttpFiltersAdapter, ChainedProxyAdapter, HttpFiltersSourceAdapter
- Interface contract testing: ActivityTracker, ChainedProxy, HttpFilters, HttpFiltersSource, MitmManager, ProxyAuthenticator, SslEngineSource
- DNS resolution testing: DefaultHostResolver, HostResolver
- SSL/Security testing: SelfSignedSslEngineSource, TrustingTrustManager
- Enum testing: ChainedProxyType, TransportProtocol, LogFormat, TimingMode, StandardField, ComputedField
- Exception testing: UnknownChainedProxyTypeException, UnknownTransportProtocolException
- Lifecycle event testing including timing metrics
- Field configuration testing with JSON parsing
- Format compliance testing for all log formats
- Timing mode testing (OFF, MINIMAL, ALL)
- NPE prevention testing for null timing data
- All 589+ tests passing
---
Breaking Changes
1. Package relocation: ActivityLogger moved from org.littleshoot.proxy.extras to org.littleshoot.proxy.extras.logging
2. New interface methods: Custom ActivityTracker implementations must implement new lifecycle methods (including clientSSLHandshakeStarted) or extend ActivityTrackerAdapter
3. Changed method signatures: clientConnected, clientSSLHandshakeSucceeded, clientDisconnected now accept FlowContext instead of InetSocketAddress
4. ActivityTracker new method: clientSSLHandshakeStarted(FlowContext) for SSL handshake timing measurement
4. Duration field names: Changed from duration_ms to http_request_processing_time_ms and client_connection_duration_ms to tcp_client_connection_duration_ms
5. LogField API Change: Removed duration parameter from extractValue() method
   - Before: extractValue(FlowContext, HttpRequest, HttpResponse, long duration)
   - After: extractValue(FlowContext, HttpRequest, HttpResponse)
   - Timing data now retrieved via flowContext.getTimingData("key")
6. LogEntryFormatter API Change: Removed durationMs parameter from format() method
   - Before: format(..., long durationMs, ...)
   - After: format(..., FlowContext, ...)
   - Timing data now retrieved via FlowContext timing map
---
Migration Guide
For ActivityTracker implementations:
// Before
```java
public class MyTracker implements ActivityTracker { ... }
```
// After - Option 1: Extend adapter (recommended)
```java
public class MyTracker extends ActivityTrackerAdapter { ... }
```
// After - Option 2: Implement new methods
```java
public class MyTracker implements ActivityTracker {
  // ... existing methods ...
  // New methods to implement
  public void clientConnected(FlowContext ctx) { }
  public void clientSSLHandshakeStarted(FlowContext ctx) { }
  public void clientSSLHandshakeSucceeded(FlowContext ctx, SSLSession ssl) { }
  public void clientDisconnected(FlowContext ctx, SSLSession ssl) { }
  public void serverConnected(FullFlowContext ctx, InetSocketAddress addr) { }
  public void serverDisconnected(FullFlowContext ctx, InetSocketAddress addr) { }
  public void connectionSaturated(FlowContext ctx) { }
  public void connectionWritable(FlowContext ctx) { }
  public void connectionTimedOut(FlowContext ctx) { }
  public void connectionExceptionCaught(FlowContext ctx, Throwable cause) { }
}
```
For log parsing:
# Old format
```
flow_id=flow-123 duration_ms=150 client_connection_duration_ms=152
```
# New format
```
flow_id=01KGNMFEFZ84ZAR511NTRAFW13 http_request_processing_time_ms=150 tcp_client_connection_duration_ms=152
```
For custom LogField implementations:
// Before
```java
public class MyField implements LogField {
  public String extractValue(FlowContext ctx, HttpRequest req, HttpResponse resp, long duration) {
    return String.valueOf(duration);  // Use duration parameter
  }
}
```
// After
```java
public class MyField implements LogField {
  public String extractValue(FlowContext ctx, HttpRequest req, HttpResponse resp) {
    Long duration = ctx.getTimingData("http_request_processing_time_ms");
    return duration != null ? String.valueOf(duration) : "-";
  }
}
```
For custom LogEntryFormatter implementations:
// Before
```java
public class MyFormatter implements LogEntryFormatter {
  public String format(FlowContext ctx, HttpRequest req, HttpResponse resp, 
                       long durationMs, ZonedDateTime now, String flowId, 
                       LogFieldConfiguration config) {
    return "duration=" + durationMs;
  }
}
```
// After
```java
public class MyFormatter implements LogEntryFormatter {
  public String format(FlowContext ctx, HttpRequest req, HttpResponse resp, 
                       ZonedDateTime now, String flowId, 
                       LogFieldConfiguration config) {
    Long duration = ctx.getTimingData("http_request_processing_time_ms");
    return "duration=" + (duration != null ? duration : "-");
  }
}
```
---
Usage Examples
Basic usage with CLI:
# Default INFO level with KEYVALUE format
```bash
./run.bash --server --port 8080 --activity_log_format KEYVALUE
```

# JSON format at INFO level

```bash
./run.bash --server --port 8080 --activity_log_format JSON --activity_log_level INFO
```

# Debug level for troubleshooting

```bash
./run.bash --server --port 8080 --activity_log_format JSON --activity_log_level DEBUG
```

# With custom field configuration
```bash
./run.bash --server --port 8080 --activity_log_format JSON \
  --activity_log_prefix_headers "X-Custom-,X-Trace-" \
  --activity_log_exclude_headers "Authorization,Cookie"
```

Programmatic configuration:
```java
LogFieldConfiguration config = LogFieldConfiguration.builder()
  .addStandardField(StandardField.TIMESTAMP)
  .addStandardField(StandardField.CLIENT_IP)
  .addStandardField(StandardField.FLOW_ID)
  .addRequestHeadersWithPrefix("X-Custom-")
  .excludeRequestHeadersMatching("Authorization|Cookie")
  .build();
ActivityLogger logger = new ActivityLogger(LogFormat.JSON, config);
```

---
Performance Impact
| Feature | Impact | Recommendation |
|-------------|-----------|-------------------|
| ULID generation | Minimal | Negligible overhead |
| Flow ID in logs | Low | Essential for tracing |
| TRACE logging | High (10-20 events/request) | Development only |
| DEBUG logging | Moderate (5-10 events/request) | Troubleshooting |
| INFO logging | Low (1 event/request) | Production standard |
| Field configuration | Minimal | Safe for production |
| Regex header matching | Moderate | Use prefix when possible |
---
15. Log Formatter Strategy Pattern
    Refactored log formatting to use Strategy pattern for better modularity.
    New Package: org.littleshoot.proxy.extras.logging.formatter
    Architecture:
    LogEntryFormatter (interface)
    └── AbstractLogEntryFormatter (base class with utilities)
    ├── ClfFormatter
    ├── ElfFormatter
    ├── W3cFormatter
    ├── JsonFormatter
    ├── LtsvFormatter
    ├── CsvFormatter
    ├── SquidFormatter
    ├── HaproxyFormatter
    └── KeyValueFormatter
    Key Changes:
- Extracted 9 format-specific methods from ActivityLogger into separate classes
- Each formatter implements LogEntryFormatter interface with format() and getSupportedFormat() methods
- LogEntryFormatterFactory provides lookup by LogFormat enum
- Common utilities (URL parsing, JSON escaping, date formatting) moved to AbstractLogEntryFormatter
  Benefits:
  | Aspect | Before | After |
  |--------|--------|-------|
  | Code Organization | 700+ lines in single class | 11 focused classes |
  | Testability | Hard to test formats in isolation | Each formatter independently testable |
  | Extensibility | Modify switch statement | Create new class implementing interface |
  | Maintainability | One giant switch, easy to break | Modular, single responsibility per class |
  API Compatibility:
- No breaking changes to public API
- ActivityLogger constructor signature unchanged
- All existing log formats produce identical output
---
16. Unified Lifecycle Event Formatting
    Extended formatters to support DEBUG/TRACE level lifecycle events using the same format as INFO logs.
    Problem: Previously, only INFO level logs respected the --activity_log_format setting. DEBUG and TRACE logs used hardcoded human-readable strings, making it difficult to parse and correlate logs across different levels.
    Solution: Extended LogEntryFormatter interface with formatLifecycleEvent() method.
    New Interface Method:
```java
String formatLifecycleEvent(
    LifecycleEvent event,
    FlowContext context,
    Map<String, Object> attributes,
    String flowId
);
```

LifecycleEvent Types:
- CLIENT_CONNECTED - TCP connection established
- CLIENT_DISCONNECTED - TCP connection closed
- CLIENT_SSL_HANDSHAKE_SUCCEEDED - SSL handshake completed
- SERVER_CONNECTED - Upstream server connection established
- SERVER_DISCONNECTED - Upstream server connection closed
- CONNECTION_SATURATED - Backpressure detected
- CONNECTION_WRITABLE - Connection ready for writing
- CONNECTION_TIMED_OUT - Idle timeout triggered
- CONNECTION_EXCEPTION_CAUGHT - Exception on connection
- REQUEST_RECEIVED - HTTP request received
- RESPONSE_SENT - HTTP response sent

Format Support:
| Format | Lifecycle Events | Example Output |
|--------|------------------|----------------|
| JSON | ✅ Full support | {"event":"client_connected","flow_id":"...","client_ip":"..."} |
| LTSV | ✅ Full support | event:client_connected	flow_id:...	client_ip:... |
| CSV | ✅ Full support | "client_connected","...","..." |
| KEYVALUE | ✅ Full support | event=client_connected flow_id=... client_ip=... |
| CLF | ❌ Not applicable | Falls back to simple format |
| ELF | ❌ Not applicable | Falls back to simple format |
| W3C | ❌ Not applicable | Falls back to simple format |
| Squid | ❌ Not applicable | Falls back to simple format |
| HAProxy | ❌ Not applicable | Falls back to simple format |
Fallback Format:
For access log formats (CLF, ELF, W3C, Squid, HAProxy) that don't support lifecycle events, the system falls back to a simple format:
[flow_id] event_name key1=value1 key2=value2
Benefits:
| Aspect | Before | After |
|--------|--------|-------|
| Consistency | INFO uses format, DEBUG/TRACE don't | All levels use same format |
| Parsing | Different parsers needed per level | Single parser for all levels |
| Correlation | Hard to correlate across levels | Easy to trace flow across levels |
| Tooling | Manual parsing for DEBUG/TRACE | Structured for log aggregation |
Example - JSON Format:
// DEBUG: Lifecycle event
```json
{event:client_connected,flow_id:01KG...,client_ip:127.0.0.1,client_address:/127.0.0.1:12345,timestamp:1738323456789}
```
// INFO: Request/response (same format)
```json
{flow_id:01KG...,client_ip:127.0.0.1,method:GET,status:200}
```
Implementation Details:
- Added logLifecycleEvent() helper method in ActivityLogger with fallback formatting
- Replaced all hardcoded DEBUG/TRACE logs with structured lifecycle event logging
- All lifecycle methods now use the configured format
- Access log formats (CLF, ELF, W3C, Squid, HAProxy) return null from formatLifecycleEvent() and use fallback formatting
- Structured formats (JSON, LTSV, CSV, KEYVALUE) provide full lifecycle event support
  Backward Compatibility:
- No changes to public API
- Existing log output remains identical
- All tests pass with new implementation
- Fallback ensures compatibility with all log formats
---
17. Centralized Timing Data Storage
    Refactored timing metrics to use FlowContext as the single source of truth.
    Problem: Timing data was passed as parameters (duration, durationMs) through multiple layers, making it hard to add new timing metrics and maintain consistency.
    Solution: Store all timing data in FlowContext's timingData map.
    Architecture Changes:
    | Component | Before | After |
    |-----------|--------|-------|
    | ActivityLogger | Calculated timing, passed to formatters | Calculates timing, stores in FlowContext |
    | LogField.extractValue() | Received duration as parameter | Retrieves all timing from FlowContext |
    | LogEntryFormatter.format() | Received durationMs as parameter | Retrieves all timing from FlowContext |
    | StandardField | Only HTTP_REQUEST_PROCESSING_TIME_MS worked | All timing fields access FlowContext |
    Timing Data Lifecycle:
    ```
    clientConnected()
        ↓
    [Store tcp_connection_start_time in FlowContext]
        ↓
    requestReceivedFromClient()
        ↓
    [Store request_start_time in FlowContext]
        ↓
    responseSentToClient()
        ↓
    [Calculate http_request_processing_time_ms, store in FlowContext]
        ↓
    clientDisconnected()
        ↓
    [Calculate tcp_client_connection_duration_ms, store in FlowContext]
    ```
    Benefits:
    - Single source of truth for timing data
    - Extensible: Add new timing metrics without changing method signatures
    - Cleaner APIs: No need to pass multiple timing parameters
    - All timing metrics accessible from any LogField implementation
    Backward Compatibility:
    - No changes to public API usage
    - ActivityLogger constructor unchanged
    - All existing log formats produce identical output
    Implementation Details:
    - FlowContext.timingData: ConcurrentHashMap<String, Long> for thread-safe storage
    - FlowContext.setTimingData(String key, Long value): Store timing metric
    - FlowContext.getTimingData(String key): Retrieve timing metric
    - All timing values in milliseconds
    - Keys use snake_case naming convention
    Migration for existing code:
    - LogField implementations: Remove duration parameter, use ctx.getTimingData() instead
    - LogEntryFormatter implementations: Remove durationMs parameter, use ctx.getTimingData() instead
    - No changes needed for configuration or CLI usage
---
References
- ULID Specification (https://github.com/ulid/spec)
- LTSV Format (http://ltsv.org/)
- W3C Extended Log File Format (https://www.w3.org/TR/WD-logfile)
- HAProxy Logging (https://cbonte.github.io/haproxy-dconv/2.4/configuration.html#logging)
- Apache SkyWalking Log & Trace Correlation (skywalking.apache.org/docs/skywalking-java/next/en/setup/service-agent/java-agent/advanced-reporters/)
- qlog: Structured Logging for Network Protocols (datatracker.ietf.org/doc/html/draft-ietf-quic-qlog-main-schema-13)
- Apache HttpComponents Logging (hc.apache.org/httpcomponents-client-5.5.x/logging.html)
- Strategy Pattern - Refactoring Guru (https://refactoring.guru/design-patterns/strategy)
- **Timing Metrics Documentation** (docs/TIMING_METRICS.md) - Comprehensive guide to timing metrics with visual diagrams
- Added lifecycle timing enrichment:
  - CLIENT_CONNECTED / CLIENT_DISCONNECTED now emit `connection_age_ms`, `time_since_last_request_ms`, and duration fields when available.
  - REQUEST_RECEIVED logs include `time_since_client_connect_ms`, `time_since_previous_request_ms`, and `time_since_server_connect_ms`.
  - SERVER_CONNECTED / SERVER_DISCONNECTED logs include `server_connect_latency_ms`, `time_since_client_request_ms`, and `tcp_server_connection_duration_ms`.
  - FlowContext reuse fixes ensure timing data persists across the entire lifecycle for both client and server connections.
