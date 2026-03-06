# LittleProxy

A high performance HTTP/HTTPS proxy written in Java, built on [Netty](https://netty.io).

[![Maven Central](https://img.shields.io/maven-central/v/io.github.littleproxy/littleproxy.svg)](https://search.maven.org/artifact/io.github.littleproxy/littleproxy)

## Quick Start

```bash
git clone git@github.com:LittleProxy/LittleProxy.git
cd LittleProxy
./run.bash
```

Proxy is now running on `http://localhost:8080`

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.littleproxy</groupId>
    <artifactId>littleproxy</artifactId>
    <version>2.6.0</version>
</dependency>
```

### Gradle

```groovy
implementation "io.github.littleproxy:littleproxy:2.6.0"
```

## Basic Usage

### Start a Simple Proxy

```java
HttpProxyServer server = DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .start();
```

### Filter HTTP Traffic

```java
HttpProxyServer server = DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .withFiltersSource(new HttpFiltersSourceAdapter() {
        public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
            return new HttpFiltersAdapter(originalRequest) {
                @Override
                public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                    // Intercept/modify requests
                    return null;
                }

                @Override
                public HttpObject serverToProxyResponse(HttpObject httpObject) {
                    // Intercept/modify responses
                    return httpObject;
                }
            };
        }
    })
    .start();
```

### Enable HTTPS Interception (MITM)

```java
HttpProxyServer server = DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .withManInTheMiddle(new SelfSignedMitmManager())
    .start();
```

For advanced MITM features, see:
- [LittleProxy-mitm](https://github.com/ganskef/LittleProxy-mitm) - Supports Android and all Java platforms
- [BrowserMob Proxy MITM](https://github.com/lightbody/browsermob-proxy/tree/master/mitm) - ECC and custom trust stores

### Clone Servers

```java
// Create multiple proxies sharing event loops
existingServer.clone().withPort(8081).start();
```

## Configuration

### Command Line

```bash
./run.bash --port 9090 --mitm --activity_log_format JSON
```

### Configuration File

```bash
./run.bash --config ./config/littleproxy.properties
```

Example `littleproxy.properties`:

```properties
port=8080
address=0.0.0.0
name=LittleProxy
idleConnectionTimeout=60
connect_timeout=30
```

### Options Reference

#### Core Options

| Option | Default | Description |
|--------|---------|-------------|
| `port` | 8080 | Port to listen on |
| `address` | 0.0.0.0:8080 | Bind address (IP:port) |
| `nic` | 0.0.0.0 | Network interface to bind |
| `name` | LittleProxy | Server name (used in thread naming) |
| `proxy_alias` | hostname | Alias for Via header |
| `server` | false | Run as persistent server |

#### Connection Options

| Option | Default | Description |
|--------|---------|-------------|
| `idleConnectionTimeout` | -1 | Idle timeout in seconds (-1 = disabled) |
| `connect_timeout` | 0 | Connect timeout in seconds (0 = disabled) |
| `max_initial_line_length` | 8192 | Max HTTP initial line length (bytes) |
| `max_header_size` | 16384 | Max header size (bytes) |
| `max_chunk_size` | 16384 | Max chunk size (bytes) |
| `allow_local_only` | false | Only accept local connections |
| `allow_requests_to_origin_server` | false | Allow direct origin requests |
| `allow_proxy_protocol` | false | Accept PROXY protocol headers |
| `send_proxy_protocol` | false | Send PROXY protocol headers |

#### Threading Options

| Option | Default | Description |
|--------|---------|-------------|
| `acceptor_threads` | auto | Acceptor thread count |
| `client_to_proxy_worker_threads` | auto | Client-to-proxy worker threads |
| `proxy_to_server_worker_threads` | auto | Proxy-to-server worker threads |

#### SSL/TLS Options

| Option | Default | Description |
|--------|---------|-------------|
| `authenticate_ssl_clients` | false | Require SSL client auth |
| `ssl_clients_trust_all_servers` | false | Trust all server certificates |
| `ssl_clients_send_certs` | false | Send client certificates |
| `ssl_clients_key_store_file_path` | null | Keystore file path |
| `ssl_clients_key_store_alias` | null | Keystore alias |
| `ssl_clients_key_store_password` | null | Keystore password |

#### Throttling Options

| Option | Default | Description |
|--------|---------|-------------|
| `throttle_read_bytes_per_second` | 0 | Read bandwidth limit (0 = unlimited) |
| `throttle_write_bytes_per_second` | 0 | Write bandwidth limit (0 = unlimited) |

#### Logging Options

| Option | Default | Description |
|--------|---------|-------------|
| `activity_log_format` | disabled | Format: KEYVALUE, CLF, ELF, JSON, LTSV, CSV, SQUID, HAPROXY, W3C |
| `activity_log_field_config` | disabled | Path to JSON field configuration |
| `activity_log_prefix_headers` | null | Comma-separated header prefixes to log |
| `activity_log_regex_headers` | null | Comma-separated regex patterns |
| `activity_log_exclude_headers` | null | Comma-separated patterns to exclude |
| `activity_log_mask_sensitive` | false | Mask sensitive header values |

#### Security Options

| Option | Default | Description |
|--------|---------|-------------|
| `dnssec` | false | Enable DNSSEC validation |
| `transparent` | false | Enable transparent proxy mode |

### Logging Configuration

Configure Log4j output via `--log_config`:

```bash
./run.bash --log_config /path/to/log4j.xml
```

## Activity Logging

LittleProxy includes built-in activity logging with multiple formats:

```java
DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .plusActivityTracker(new ActivityLogger(LogFormat.JSON))
    .start();
```

### Supported Formats

| Format | Description | Example |
|--------|-------------|---------|
| `CLF` | Common Log Format | `127.0.0.1 - - [date] "GET / HTTP/1.1" 200 1234` |
| `ELF` | Extended (Combined) Log | `127.0.0.1 - - [date] "GET /" 200 1234 "referer" "UA"` |
| `JSON` | JSON format | `{"timestamp":"...","method":"GET",...}` |
| `LTSV` | Labeled Tab-Separated | `time:...\thost:127.0.0.1\t...` |
| `CSV` | Comma-Separated | `"timestamp","127.0.0.1","GET",...` |
| `W3C` | W3C Extended Log | `2025-12-24 00:00:00 127.0.0.1 GET / 200` |
| `SQUID` | Squid access log | Squid-compatible format |
| `HAPROXY` | HAProxy-style | Timing-focused format |

### Custom Field Configuration

```java
LogFieldConfiguration fieldConfig = LogFieldConfiguration.builder()
    .addStandardField(StandardField.TIMESTAMP)
    .addStandardField(StandardField.CLIENT_IP)
    .addStandardField(StandardField.METHOD)
    .addRequestHeadersWithPrefix("X-Custom-")
    .excludeRequestHeadersMatching("Authorization|Cookie")
    .build();

DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .plusActivityTracker(new ActivityLogger(LogFormat.JSON, fieldConfig))
    .start();
```

Or via CLI:

```bash
./run.bash --activity_log_format JSON \
    --activity_log_prefix_headers "X-Custom-,X-Trace-" \
    --activity_log_exclude_headers "Authorization,Cookie"
```

## Advanced Topics

- **[Performance and Logging Guide](docs/PERFORMANCE_AND_LOGGING.md)** - Async logging, filters, best practices
- **[Timing Metrics](docs/TIMING_METRICS.md)** - Request/response timing, SSL handshake metrics
- **[Request Handling Architecture](docs/LittleProxy_Request_Handling_Architecture.md)** - Internal architecture

## Support

- **Google Group**: [littleproxy2](https://groups.google.com/forum/#!forum/littleproxy2)
- **Subscribe**: Email [LittleProxy2+subscribe@googlegroups.com](mailto:LittleProxy2+subscribe@googlegroups.com)

## Acknowledgments

Thanks to [The Measurement Factory](http://www.measurement-factory.com/) for HTTP standards compliance testing with [Co-Advisor](http://coad.measurement-factory.com/).
