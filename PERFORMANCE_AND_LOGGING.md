# LittleProxy Performance and Logging Guide

This guide covers logging performance optimization techniques and configuration options for LittleProxy.

## Table of Contents

- [Logging Modes](#logging-modes)
  - [Synchronous Logging](#synchronous-logging)
  - [Asynchronous Logging](#asynchronous-logging)
- [Performance Considerations](#performance-considerations)
- [Logging Configuration](#logging-configuration)
- [Log Filtering and Rate Limiting](#log-filtering-and-rate-limiting)
  - [BurstFilter Configuration](#burstfilter-configuration)
  - [Custom Filter Implementation](#custom-filter-implementation)
- [Activity Logging](#activity-logging)
  - [LogFieldConfiguration](#logfieldconfiguration)
  - [Field Name Transformers](#field-name-transformers)
  - [Value Transformers](#value-transformers)
  - [Header Pattern Types](#header-pattern-types)
  - [CLI Options for Field Configuration](#cli-options-for-field-configuration)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

## Logging Modes

### Synchronous Logging

**Default Mode**: Synchronous logging is the default behavior and provides reliable logging with immediate disk writes.

**Characteristics:**
- ✅ Simple and reliable
- ✅ Logs are immediately written to disk
- ❌ Higher I/O overhead
- ❌ Can impact proxy performance under heavy load
- ❌ Slower response times during peak traffic

**Configuration File**: `src/main/resources/littleproxy_default_log4j2.xml`

**Appender Type**: `RollingFile` with immediate flush

**Example Usage:**
```bash
./run.bash --server --config ./config/littleproxy.properties --port 9092
```

### Asynchronous Logging

**Performance Mode**: Asynchronous logging significantly improves performance by buffering log events and writing them in batches.

**Characteristics:**
- ✅ Much lower I/O overhead
- ✅ Better performance under heavy load
- ✅ Reduced disk I/O operations
- ✅ Configurable buffer sizes
- ❌ Slight risk of log loss on JVM crash
- ❌ Logs may be delayed during shutdown

**Configuration File**: `src/main/resources/littleproxy_async_log4j2.xml`

**Appender Type**: `RollingRandomAccessFile` with `immediateFlush="false"`

**Async Features:**
- `AsyncRoot` for root logger
- `AsyncLogger` for all specific loggers
- `includeLocation="true"` for better debugging

**Performance Optimizations:**
- **Buffer Size**: Configurable via Log4j2 system properties
- **Batch Writing**: Logs are written in batches rather than individually
- **Reduced I/O**: `immediateFlush="false"` reduces disk operations
- **Larger Files**: 250MB file size vs 50MB in sync mode reduces rollover frequency

**Example Usage:**
```bash
# Using the async_logging_default flag
./run.bash --async_logging_default --server --config ./config/littleproxy.properties --port 9092

# Direct Java command
java -server -XX:+HeapDumpOnOutOfMemoryError -Xmx800m \
  -jar ./target/littleproxy-2.6.1-SNAPSHOT-littleproxy-shade.jar \
  --server --config ./config/littleproxy.properties --port 9092 \
  --log_config ./target/classes/littleproxy_async_log4j2.xml
```

## Performance Considerations

### When to Use Synchronous Logging

- **Development environments** where immediate log visibility is important
- **Debugging scenarios** where you need real-time log output
- **Low-traffic productions** where performance impact is negligible
- **Compliance requirements** that mandate immediate log persistence

### When to Use Asynchronous Logging

- **High-traffic productions** where performance is critical
- **Load testing environments** to get accurate performance metrics
- **Resource-constrained systems** where I/O reduction is needed
- **Burst traffic scenarios** where logging can become a bottleneck

### Performance Impact Comparison

| Metric | Synchronous | Asynchronous | Improvement |
|--------|-------------|--------------|-------------|
| **Throughput** | Baseline | +30-50% | 1.3-1.5x |
| **Latency** | Baseline | -40-60% | 0.4-0.6x |
| **Disk I/O** | High | Low | 5-10x less |
| **CPU Usage** | Moderate | Lower | 10-20% less |
| **Memory Usage** | Low | Slightly higher | Buffer overhead |

## Logging Configuration

### Default Configuration (Synchronous)

```xml
<!-- Synchronous rolling file appender -->
<RollingFile name="ROLLING_TEXT_FILE" fileName="logs/app.log"
             filePattern="logs/$${date:yyyy-MM}/app-%d{MM-dd-yyyy}-%i.log.gz">
    <PatternLayout>
        <Pattern>%d{ISO8601} %-5p [%t] %c{2} (%F:%L).%M() - %m%n</Pattern>
    </PatternLayout>
    <Policies>
        <TimeBasedTriggeringPolicy interval="6" modulate="true"/>
        <SizeBasedTriggeringPolicy size="50 MB"/>
    </Policies>
</RollingFile>
```

### Async Configuration

```xml
<!-- Asynchronous rolling file appender -->
<RollingRandomAccessFile name="ASYNC_ROLLING_FILE" fileName="logs/app-async.log"
                         filePattern="logs/$${date:yyyy-MM}/app-async-%d{yyyy-MM-dd-HH}-%i.log.gz"
                         immediateFlush="false">
    <PatternLayout>
        <Pattern>%d{ISO8601} %-5p [%t] %c{2} (%F:%L).%M() - %m%n</Pattern>
    </PatternLayout>
    <Policies>
        <TimeBasedTriggeringPolicy interval="6" modulate="true"/>
        <SizeBasedTriggeringPolicy size="250 MB"/>
    </Policies>
</RollingRandomAccessFile>

<!-- Async loggers -->
<AsyncRoot level="INFO" includeLocation="true">
    <AppenderRef ref="STD_OUT"/>
    <AppenderRef ref="ASYNC_ROLLING_FILE"/>
</AsyncRoot>
<AsyncLogger name="org.littleshoot.proxy" level="INFO" includeLocation="true"/>
```

### Advanced Configuration Options

**Log4j2 System Properties:**

```bash
# Increase async logger ring buffer size (default: 262144)
java -Dlog4j2.AsyncLogger.RingBufferSize=1048576 ...

# Increase async logger queue size
java -Dlog4j2.AsyncLoggerConfig.RingBufferSize=1048576 ...

# Disable location tracking for better performance
java -Dlog4j2.includeLocation=false ...
```

## Log Filtering and Rate Limiting

### BurstFilter Configuration

Log4j2 provides a `BurstFilter` that can limit the number of log events within a time period to prevent log flooding.

**Example Configuration:**

```xml
<Configuration>
    <Appenders>
        <Console name="STD_OUT">
            <BurstFilter level="INFO" rate="100" maxBurst="50"/>
            <PatternLayout pattern="%d{ISO8601} %-5p [%t] %c{2} - %m%n"/>
        </Console>
    </Appenders>
    <!-- Rest of configuration -->
</Configuration>
```

**BurstFilter Parameters:**

- `level`: The log level to filter (INFO, DEBUG, WARN, etc.)
- `rate`: Maximum number of log events per second
- `maxBurst`: Maximum number of events allowed in a burst

**Example Scenarios:**

```xml
<!-- Limit INFO logs to 100 per second, max 50 burst -->
<BurstFilter level="INFO" rate="100" maxBurst="50"/>

<!-- Limit DEBUG logs to 50 per second, max 20 burst -->
<BurstFilter level="DEBUG" rate="50" maxBurst="20"/>

<!-- Very restrictive for high-volume scenarios -->
<BurstFilter level="INFO" rate="10" maxBurst="5"/>
```

### Custom Filter Implementation

For more sophisticated filtering, you can implement custom Log4j2 filters using Java or Groovy scripts.

#### Java Custom Filter Example

**Example Custom Filter:**

```java
package org.littleshoot.proxy.logging;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * Custom filter to exclude specific request patterns
 */
public class RequestPatternFilter extends AbstractFilter {
    
    private final String[] excludedPatterns;
    
    public RequestPatternFilter(String[] excludedPatterns) {
        this.excludedPatterns = excludedPatterns;
    }
    
    @Override
    public Result filter(LogEvent event) {
        String message = event.getMessage().getFormattedMessage();
        
        // Check if message matches any excluded pattern
        for (String pattern : excludedPatterns) {
            if (message.contains(pattern)) {
                return Result.DENY; // Exclude this log
            }
        }
        
        return Result.NEUTRAL; // Allow this log
    }
    
    @Override
    public Result filter(org.apache.logging.log4j.core.Logger logger, org.apache.logging.log4j.Level level, 
                        org.apache.logging.log4j.Marker marker, String msg, Object... params) {
        return filterLogEvent(level, marker, msg, params);
    }
    
    @Override
    public Result filter(org.apache.logging.log4j.core.Logger logger, org.apache.logging.log4j.Level level, 
                        org.apache.logging.log4j.Marker marker, Object msg, Throwable t) {
        return filterLogEvent(level, marker, msg, t);
    }
    
    @Override
    public Result filter(org.apache.logging.log4j.core.Logger logger, org.apache.logging.log4j.Level level, 
                        org.apache.logging.log4j.Marker marker, Message msg, Throwable t) {
        return filterLogEvent(level, marker, msg, t);
    }
    
    private Result filterLogEvent(org.apache.logging.log4j.Level level, org.apache.logging.log4j.Marker marker, 
                                  Object msg, Object... params) {
        if (msg instanceof String) {
            String message = (String) msg;
            for (String pattern : excludedPatterns) {
                if (message.contains(pattern)) {
                    return Result.DENY;
                }
            }
        }
        return Result.NEUTRAL;
    }
}
```

#### Groovy Script Filter Example

Log4j2 supports Groovy scripts for dynamic filtering without compilation. This is perfect for sampling, conditional logging, or complex logic.

**Example: Sampling Filter (log only 10% of messages)**

```xml
<Configuration status="WARN">
    <Filters>
        <ScriptFilter onMatch="ACCEPT" onMismatch="DENY">
            <Script language="groovy">
                <![CDATA[
                    // Example: Log only 10% of messages for sampling
                    // This reduces log volume while maintaining representative samples
                    return (Math.random() < 0.1)
                ]]>
            </Script>
        </ScriptFilter>
    </Filters>
    <Appenders>
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </Console>
    </Appenders>
    <Loggers>
        <Root level="info">
            <AppenderRef ref="Console"/>
        </Root>
    </Loggers>
</Configuration>
```

**Example: Conditional Filter (exclude health checks)**

```xml
<Configuration status="WARN">
    <Filters>
        <ScriptFilter onMatch="ACCEPT" onMismatch="DENY">
            <Script language="groovy">
                <![CDATA[
                    // Example: Exclude health check and ping requests
                    def message = logEvent.getMessage().getFormattedMessage()
                    
                    // List of patterns to exclude
                    def excludedPatterns = ['/health', '/ping', 'favicon.ico']
                    
                    // Check if message contains any excluded pattern
                    for (pattern in excludedPatterns) {
                        if (message.contains(pattern)) {
                            return false // Exclude this log
                        }
                    }
                    
                    return true // Allow this log
                ]]>
            </Script>
        </ScriptFilter>
    </Filters>
    <!-- Rest of configuration -->
</Configuration>
```

**Example: Time-Based Filter (business hours only)**

```xml
<Configuration status="WARN">
    <Filters>
        <ScriptFilter onMatch="ACCEPT" onMismatch="DENY">
            <Script language="groovy">
                <![CDATA[
                    // Example: Log only during business hours (9AM-5PM)
                    def now = new Date()
                    def calendar = Calendar.getInstance()
                    calendar.setTime(now)
                    
                    def hour = calendar.get(Calendar.HOUR_OF_DAY)
                    def isBusinessHours = hour >= 9 && hour < 17
                    
                    // Also log weekends but at reduced rate
                    def dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                    def isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
                    
                    if (isBusinessHours) {
                        return true // Always log during business hours
                    } else if (isWeekend) {
                        return (Math.random() < 0.05) // Log 5% of weekend traffic
                    } else {
                        return (Math.random() < 0.2) // Log 20% of after-hours traffic
                    }
                ]]>
            </Script>
        </ScriptFilter>
    </Filters>
    <!-- Rest of configuration -->
</Configuration>
```

**Groovy Script Filter Benefits:**

- ✅ **No compilation needed**: Scripts are interpreted at runtime
- ✅ **Dynamic logic**: Can use complex conditions and external data
- ✅ **Easy to modify**: Change filtering logic without recompiling
- ✅ **Powerful**: Access to full Groovy language features
- ✅ **Performance**: Still efficient for most use cases

**Script Filter Parameters:**

- `onMatch`: What to do when script returns true (ACCEPT/DENY/NEUTRAL)
- `onMismatch`: What to do when script returns false (ACCEPT/DENY/NEUTRAL)
- `language`: Script language (groovy, javascript, etc.)

**Available Variables in Script:**

- `logEvent`: The current LogEvent object
- `loggerName`: Name of the logger
- `level`: Log level
- `message`: Formatted message
- `marker`: Marker (if any)
- `throwable`: Exception (if any)

**Performance Considerations:**

- Script filters add some overhead compared to compiled filters
- Use for complex logic that's hard to implement in Java
- Test script performance before deploying to production
- Consider caching results for repeated patterns

**XML Configuration for Custom Filter:**

```xml
<Configuration>
    <Appenders>
        <Console name="STD_OUT">
            <RequestPatternFilter excludedPatterns="health-check,ping,favicon.ico"/>
            <PatternLayout pattern="%d{ISO8601} %-5p [%t] %c{2} - %m%n"/>
        </Console>
    </Appenders>
</Configuration>
```

### Filter Examples by Use Case

**1. Exclude Health Check Requests:**
```xml
<BurstFilter level="INFO" rate="100" maxBurst="50"/>
```

**2. Limit Debug Logs in Production:**
```xml
<ThresholdFilter level="INFO" onMatch="ACCEPT" onMismatch="DENY"/>
```

**3. Filter by Request Type:**
```xml
<RegexFilter regex=".*(health|ping|status).*" onMatch="DENY" onMismatch="NEUTRAL"/>
```

**4. Rate Limiting for Specific Loggers:**
```xml
<Logger name="org.littleshoot.proxy.extras.ActivityLogger" level="INFO">
    <BurstFilter level="INFO" rate="200" maxBurst="100"/>
</Logger>
```

## Activity Logging

Activity logging in LittleProxy captures HTTP request/response details. This can be a significant performance factor. The logging system is located in the `org.littleshoot.proxy.extras.logging` package.

### ActivityLogger Three-Tier Logging Strategy

The `ActivityLogger` class implements a sophisticated three-tier logging strategy that provides different levels of detail based on the log level configuration:

#### TRACE Level - Detailed Diagnostics

**Purpose**: Method entry tracing, state transitions, and detailed context information for deep debugging.

**When to use**: 
- Development and debugging
- Troubleshooting complex connection issues
- Understanding the flow of requests through the proxy

**What is logged**:
- Method entry points with full context (flow ID, thread name, timestamps)
- State transitions (client connected, SSL handshake started, etc.)
- Connection saturation/writability events
- Exception details with stack traces

**Example output**:
```
TRACE: ENTER clientConnected - address=/192.168.1.1:12345, thread=netty-worker-1, timestamp=1234567890
TRACE: ENTER serverConnected - serverAddress=example.com:443, flowContext=FullFlowContext@abc123, timestamp=1234567891
TRACE: Connection saturated - side=server, flowContext=FullFlowContext@abc123
```

**Performance impact**: High - generates many log entries per request. Use only during debugging.

#### DEBUG Level - Essential Operations

**Purpose**: High-level lifecycle events and operational information for production troubleshooting.

**When to use**:
- Production monitoring
- Operational dashboards
- Troubleshooting without overwhelming logs

**What is logged**:
- Client connections/disconnections with duration
- Server connections/disconnections with timing
- SSL handshake completion
- Connection timeouts
- Exception summaries (type and message)
- Formatted log entries (CLF, JSON, etc.)

**Example output**:
```
DEBUG: Client connected: /192.168.1.1:12345
DEBUG: Server connected: example.com:443 (took 45ms)
DEBUG: Client SSL handshake succeeded: /192.168.1.1:12345, duration: 12ms
DEBUG: Client disconnected: /192.168.1.1:12345, duration: 150ms
DEBUG: Connection timed out: server
DEBUG: Connection exception caught: client, type: IOException, message: Connection reset by peer
```

**Performance impact**: Moderate - one log entry per lifecycle event. Safe for production with filtering.

#### INFO Level - Complete Interaction Summary

**Purpose**: Single structured log entry per request/response cycle with all aggregated metrics.

**When to use**:
- Production access logging
- Analytics and monitoring
- Audit trails
- Request tracing

**What is logged**:
- Single line per request with all metrics:
  - Flow ID for request tracing
  - Client and server addresses
  - HTTP method, URI, protocol
  - Response status and bytes
  - Duration metrics (total, server connect, SSL handshake)
  - Saturation counts
  - Exception information

**Example output**:
```
INFO: flow_id=flow-123 client_ip=192.168.1.1 client_port=12345 server_ip=10.0.0.1 server_port=443 method=GET uri="/api/status" protocol=HTTP/1.1 status=200 bytes=1234 duration_ms=150 server_connect_ms=45 ssl_handshake_ms=12 client_saturations=0 server_saturations=0 exception=none
```

**Performance impact**: Low - single log entry per request. Recommended for production.

#### Log Level Configuration

Configure log levels in your Log4j2 configuration:

```xml
<!-- Development: Enable all levels -->
<Logger name="org.littleshoot.proxy.extras.logging.ActivityLogger" level="TRACE"/>

<!-- Production: INFO for operational visibility -->
<Logger name="org.littleshoot.proxy.extras.logging.ActivityLogger" level="INFO"/>

<!-- Production with debugging: DEBUG for troubleshooting -->
<Logger name="org.littleshoot.proxy.extras.logging.ActivityLogger" level="DEBUG"/>
```

#### Performance Considerations by Level

| Log Level | Events/Request | Performance | Use Case |
|-----------|----------------|-------------|----------|
| **TRACE** | 10-20 | 🐢 Slowest | Development only |
| **DEBUG** | 5-10 | 🏃 Moderate | Production troubleshooting |
| **INFO** | 1 | ⚡ Fastest | Production standard |

**Recommendation**: Use INFO for production, DEBUG for troubleshooting, TRACE for development only.

### Activity Log Formats

**CLF (Common Log Format):**
```bash
--activity_log_format CLF
```

**ELF (Extended Log Format):**
```bash
--activity_log_format ELF
```

**JSON:**
```bash
--activity_log_format JSON
```

**SQUID:**
```bash
--activity_log_format SQUID
```

**W3C:**
```bash
--activity_log_format W3C
```

**LTSV (Labeled Tab-Separated Values):**
```bash
--activity_log_format LTSV
```

**CSV (Comma-Separated Values):**
```bash
--activity_log_format CSV
```

**HAPROXY:**
```bash
--activity_log_format HAPROXY
```

### LogFieldConfiguration

The `LogFieldConfiguration` class provides fine-grained control over which fields are logged. You can configure it programmatically or load it from a JSON file.

#### Programmatic Configuration

```java
import org.littleshoot.proxy.extras.logging.*;

LogFieldConfiguration fieldConfig = LogFieldConfiguration.builder()
    // Add standard fields
    .addStandardField(StandardField.TIMESTAMP)
    .addStandardField(StandardField.CLIENT_IP)
    .addStandardField(StandardField.METHOD)
    .addStandardField(StandardField.URI)
    .addStandardField(StandardField.STATUS)
    
    // Log headers by prefix
    .addRequestHeadersWithPrefix("X-Custom-")
    .addResponseHeadersWithPrefix("X-RateLimit-")
    
    // Log headers by regex pattern
    .addRequestHeadersMatching("X-.*-Id")
    
    // Exclude sensitive headers
    .excludeRequestHeadersMatching("Authorization|Cookie")
    
    // Add computed fields
    .addComputedField(ComputedField.GEOLOCATION_COUNTRY)
    .addComputedField(ComputedField.RESPONSE_TIME_CATEGORY)
    
    .build();

ActivityLogger logger = new ActivityLogger(LogFormat.JSON, fieldConfig);
```

#### JSON Configuration File

Create a JSON configuration file for easier maintenance:

```json
{
  "standardFields": ["TIMESTAMP", "CLIENT_IP", "METHOD", "URI", "STATUS"],
  "prefixHeaders": [
    {
      "prefix": "X-Custom-",
      "fieldNameTransformer": "lower_underscore"
    },
    {
      "prefix": "X-Trace-",
      "fieldNameTransformer": "remove_prefix"
    }
  ],
  "regexHeaders": [
    {
      "pattern": "X-.*-Id",
      "fieldNameTransformer": "lower_underscore"
    }
  ],
  "excludeHeaders": [
    {
      "pattern": "Authorization|Cookie",
      "valueTransformer": "mask_full"
    }
  ],
  "computedFields": ["GEOLOCATION_COUNTRY", "RESPONSE_TIME_CATEGORY"]
}
```

Load the configuration:

```java
LogFieldConfiguration config = LogFieldConfigurationFactory.fromJsonFile("config.json");
```

Or via CLI:

```bash
./run.bash --activity_log_format JSON --activity_log_field_config ./config.json
```

### Field Name Transformers

Field name transformers convert HTTP header names to log field names:

| Transformer | Description | Example |
|-------------|-------------|---------|
| `lower_underscore` | Lowercase with underscores | `X-Custom-Auth` → `x_custom_auth` |
| `remove_prefix` | Remove X- prefix, lowercase | `X-Trace-Id` → `trace_id` |
| `lower_hyphen` | Lowercase with hyphens | `X-Custom-Auth` → `x-custom-auth` |
| `upper_underscore` | Uppercase with underscores | `X-Custom-Auth` → `X_CUSTOM_AUTH` |

### Value Transformers

Value transformers modify header values before logging (useful for masking sensitive data):

| Transformer | Description | Example |
|-------------|-------------|---------|
| `mask_sensitive` | Show first/last 4 chars | `secret-token-123` → `secr****t-123` |
| `mask_full` | Complete masking | `secret` → `****` |
| `mask_email` | Mask email addresses | `user@example.com` → `us***@example.com` |
| `truncate_100` | Truncate to 100 chars | Long value → `First 100 chars...` |

### Header Pattern Types

**Prefix Patterns:**
```java
// Log all headers starting with X-Custom-
builder.addRequestHeadersWithPrefix("X-Custom-");

// With custom field name transformer
builder.addRequestHeadersWithPrefix("X-RateLimit-", 
    name -> name.replace("X-RateLimit-", "").toLowerCase());
// Result: X-RateLimit-Limit → limit
```

**Regex Patterns:**
```java
// Log all headers matching X-*-Id pattern
builder.addRequestHeadersMatching("X-.*-Id");
// Matches: X-Request-Id, X-Trace-Id, X-Session-Id

// With value transformer for masking
builder.addRequestHeadersMatching("X-API-.*", 
    name -> name.toLowerCase().replaceAll("[^a-z0-9]", "_"),
    value -> value.length() > 8 ? value.substring(0, 4) + "****" + value.substring(value.length() - 4) : value);
```

**Exclude Patterns:**
```java
// Log all headers EXCEPT matching patterns
builder.excludeRequestHeadersMatching("Authorization|Cookie|X-API-Key");
```

### CLI Options for Field Configuration

**Basic inline options:**
```bash
# Log headers by prefix
./run.bash --activity_log_format JSON --activity_log_prefix_headers "X-Custom-,X-Trace-"

# Log headers by regex
./run.bash --activity_log_format JSON --activity_log_regex_headers "X-.*-Id"

# Exclude sensitive headers
./run.bash --activity_log_format JSON --activity_log_exclude_headers "Authorization,Cookie"

# Combined
./run.bash --activity_log_format JSON \
  --activity_log_prefix_headers "X-Custom-" \
  --activity_log_regex_headers "X-.*-Id" \
  --activity_log_exclude_headers "Authorization,Cookie"
```

**File-based configuration:**
```bash
./run.bash --activity_log_format JSON --activity_log_field_config ./logging-config.json
```

**Hybrid (file + CLI overrides):**
```bash
./run.bash --activity_log_format JSON \
  --activity_log_field_config ./base-config.json \
  --activity_log_exclude_headers "X-Secret-Token"
```

### Activity Logging Performance Impact

| Format | Performance | Use Case |
|--------|-------------|----------|
| **CLF** | ⚡ Fastest | Production, high volume |
| **ELF** | ⚡ Fast | Extended logging needs |
| **JSON** | 🏃 Moderate | Analytics, structured logging |
| **SQUID** | 🏃 Moderate | Squid proxy compatibility |
| **W3C** | 🏃 Moderate | Web standards compliance |
| **LTSV** | 🏃 Moderate | Machine-readable logs |
| **CSV** | 🏃 Moderate | Spreadsheet analysis |
| **HAPROXY** | 🏃 Moderate | HAProxy compatibility |

### Activity Logging Best Practices

1. **Disable in Development**: omit the `--activity_log_format` flag when not needed
2. **Use CLF in Production**: Fastest format for high-volume scenarios
3. **Use JSON with Field Configuration**: For structured logging with custom fields
4. **Sample Activity Logs**: Consider sampling (every Nth request)
5. **Separate Activity Logs**: Use different files for access logs vs application logs
6. **Exclude Sensitive Headers**: Always exclude `Authorization`, `Cookie`, `X-API-Key` in production
7. **Mask Sensitive Values**: Use value transformers for tokens, passwords, PII

**Example with Activity Logging:**
```bash
# Async logging with CLF activity format (best performance)
./run.bash --async_logging_default --server --config ./config/littleproxy.properties \
  --port 9092 --activity_log_format CLF

# Sync logging with JSON activity format (structured logging)
./run.bash --server --config ./config/littleproxy.properties \
  --port 9092 --activity_log_format JSON

# JSON with custom field configuration via CLI
./run.bash --server --config ./config/littleproxy.properties \
  --port 9092 --activity_log_format JSON \
  --activity_log_prefix_headers "X-Custom-,X-Trace-" \
  --activity_log_exclude_headers "Authorization,Cookie"

# JSON with configuration file (recommended for complex setups)
./run.bash --server --config ./config/littleproxy.properties \
  --port 9092 --activity_log_format JSON \
  --activity_log_field_config ./logging-config.json
```

### Performance Impact of Field Configuration

| Configuration | Performance Impact | Best For |
|---------------|-------------------|----------|
| **No field config** | ⚡ Minimal | Default logging |
| **Standard fields only** | ⚡ Fast | Production, high volume |
| **Prefix patterns** | 🏃 Moderate | Custom header logging |
| **Regex patterns** | 🐢 Slower | Complex matching needs |
| **Exclude patterns** | 🏃 Moderate | Security/privacy compliance |
| **Value transformers** | 🐢 Slower | Sensitive data masking |

**Recommendations:**
- Use prefix patterns instead of regex when possible (faster)
- Limit the number of regex patterns (each adds overhead)
- Apply exclude patterns early to avoid processing sensitive headers
- Cache field configurations instead of rebuilding for each request
- Use `mask_sensitive` transformer only when necessary

## Best Practices

### Logging Configuration

1. **Use Async for Production**: Always use `--async_logging_default` in production
2. **Keep Sync for Development**: Use default sync logging during development
3. **Monitor Log Growth**: Set appropriate file sizes and rotation policies
4. **Test Configuration**: Validate Log4j2 configuration before deployment

### Performance Optimization

1. **Tune Buffer Sizes**: Adjust `log4j2.AsyncLogger.RingBufferSize` based on load
2. **Limit Location Info**: Use `includeLocation="false"` for production
3. **Filter Early**: Apply filters at the appender level
4. **Use Appropriate Levels**: DEBUG for development, INFO for production

### Monitoring and Maintenance

1. **Monitor Log Files**: Check disk usage regularly
2. **Rotate Logs**: Configure proper rotation policies
3. **Archive Old Logs**: Implement log archiving strategy
4. **Alert on Errors**: Set up monitoring for ERROR level logs

## Troubleshooting

### Common Issues and Solutions

**Issue: No logs appearing with async mode**
- **Solution**: Check that `littleproxy_async_log4j2.xml` is in the correct location
- **Solution**: Verify file permissions on log directory
- **Solution**: Check for Log4j2 configuration errors

**Issue: High CPU usage with logging**
- **Solution**: Switch to async logging mode
- **Solution**: Reduce log level from DEBUG to INFO
- **Solution**: Apply BurstFilter to limit log volume

**Issue: Disk full due to logs**
- **Solution**: Configure proper rotation policies
- **Solution**: Increase file size limits
- **Solution**: Implement log archiving

**Issue: Log4j2 configuration errors**
- **Solution**: Check XML syntax
- **Solution**: Validate with `status="TRACE"` in configuration
- **Solution**: Ensure all referenced appenders exist

### Debugging Log4j2 Configuration

Add debug output to Log4j2:

```xml
<Configuration status="TRACE" monitorInterval="15">
    <!-- Rest of configuration -->
</Configuration>
```

**Debug Levels:**
- `OFF`: No internal logging
- `ERROR`: Only errors
- `WARN`: Warnings and errors
- `INFO`: Informational messages
- `DEBUG`: Debug information
- `TRACE`: Verbose debugging

### Checking Async Logger Status

```bash
# Check async logger buffer status
java -Dlog4j2.AsyncLoggerConfig.StatusLogger.level=INFO \
  -jar ./target/littleproxy-2.6.1-SNAPSHOT-littleproxy-shade.jar \
  --server --config ./config/littleproxy.properties --port 9092
```

## Advanced Topics

### Custom Appender Implementation

For specialized logging needs, implement custom appenders:

```java
@Plugin(name = "CustomAppender", category = "Core", elementType = "appender", printObject = true)
public class CustomAppender extends AbstractAppender {
    
    protected CustomAppender(String name, Filter filter, Layout<? extends Serializable> layout) {
        super(name, filter, layout);
    }
    
    @Override
    public void append(LogEvent event) {
        // Custom logging logic
        byte[] bytes = getLayout().toByteArray(event);
        // Send to custom destination (database, network, etc.)
    }
}
```

### Dynamic Log Level Adjustment

Change log levels at runtime:

```java
// Get the logger context
LoggerContext context = (LoggerContext) LogManager.getContext(false);
Configuration config = context.getConfiguration();

// Adjust log level
LoggerConfig loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
loggerConfig.setLevel(Level.DEBUG);

// Update configuration
context.updateLoggers();
```

### Log Enrichment

Add contextual information to logs:

```java
// Use ThreadContext to add contextual data
ThreadContext.put("requestId", UUID.randomUUID().toString());
ThreadContext.put("clientIp", clientAddress);

try {
    // Process request - logs will include context data
    logger.info("Processing request");
} finally {
    ThreadContext.clear();
}
```

**Pattern Layout with Context:**
```xml
<PatternLayout pattern="%d{ISO8601} %-5p [%t] %c{2} [requestId=%X{requestId}, clientIp=%X{clientIp}] - %m%n"/>
```

## Summary

This guide provides comprehensive information on optimizing LittleProxy logging performance:

- **Synchronous vs Asynchronous**: Choose based on your performance needs
- **Configuration Options**: Default and async configurations provided
- **Filtering**: BurstFilter and custom filters for rate limiting
- **Activity Logging**: Format options and performance considerations
- **Best Practices**: Production-ready recommendations
- **Troubleshooting**: Common issues and solutions

For most production environments, **asynchronous logging with CLF activity format** provides the best balance of performance and functionality:

```bash
./run.bash --async_logging_default --server --config ./config/littleproxy.properties \
  --port 9092 --activity_log_format CLF
```