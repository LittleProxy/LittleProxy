This is an updated fork of adamfisk's LittleProxy.  The original project appears
to have been abandoned.  Because it's so incredibly useful, it's being brought
back to life in this repository.

LittleProxy is a high performance HTTP proxy written in Java atop Trustin Lee's
excellent [Netty](http://netty.io) event-based networking library. It's quite
stable, performs well, and is easy to integrate into your projects.

# Usage

## Command Line

One option is to clone LittleProxy and run it from the command line. This is as simple as running the following commands :

```
$ git clone git@github.com:LittleProxy/LittleProxy.git
$ cd LittleProxy
$ ./run.bash
```

### Options

Multiple options can be passed to the script as arguments. The following options are supported :

#### Config File

This will start LittleProxy with the configuration (path relative to the working directory or absolute)
specified in the given file.

```bash
$ ./run.bash --config path/to/config/littleproxy.properties
```

You can, for example, run the shell script at the root project directory as a server, pointing 
to the provided _littleproxy.properties_ file :

```bash
$ ./run.bash --server --config ./config/littleproxy.properties
```

##### config file description

The config file is a properties file with the following properties :
- `dnssec` : boolean value to enable/disable DNSSEC validation (default : `false`)
- `transparent` : boolean value to enable/disable transparent proxy mode (default : `false`)
- `idleConnectionTimeout` : integer value to set the idle connection timeout in seconds (default : `-1`, i.e. no timeout)
- `connect_timeout` : integer value to set the connect timeout in seconds (default : `0`, i.e. no timeout)
- `max_initial_line_length` : integer value to set the max initial line length in bytes (default : `8192`)
- `max_header_size` : integer value to set the max header size in bytes (default : `16384`)
- `max_chunk_size` : integer value to set the max chunk size in bytes (default : `16384`)
- `name` : string value to set the proxy server name (default : `LittleProxy`)
- `address` : string value to set the proxy server address (default : `0.0.0.0:8080`)
- `port` : integer value to set the proxy server port (default : `8080`)
- `nic` : string value to set the network interface card (default : `0.0.0.0`)
- `proxy_alias` : string value to set the proxy alias (default : hostname of the machine)
- `allow_local_only` : boolean value to allow only local connections (default : `false`)
- `authenticate_ssl_clients` : boolean value to enable/disable SSL client authentication (default : `false`)
- `ssl_clients_trust_all_servers` : boolean value to trust all servers (default : `false`)
- `ssl_clients_send_certs` : boolean value to send certificates (default : `false`)
- `ssl_clients_key_store_file_path` : string value to set the key store file path (default : `null`)
- `ssl_clients_key_store_alias` : string value to set the key store alias (default : `null`)
- `ssl_clients_key_store_password` : string value to set the key store password (default : `null`)
- `throttle_read_bytes_per_second` : integer value to set the throttle read bytes per second (default : `0`)
- `throttle_write_bytes_per_second` : integer value to set the throttle write bytes per second (default : `0`)
- `allow_requests_to_origin_server` : boolean value to allow requests to origin server (default : `false`)
- `allow_proxy_protocol` : boolean value to allow proxy protocol (default : `false`)
- `send_proxy_protocol` : boolean value to send proxy protocol header (default : `false`)
- `activity_log_format` : string value to set the activity log format (KEYVALUE, CLF, ELF, JSON, LTSV, CSV, SQUID, HAPROXY, W3C) (default: disabled)
- `activity_log_field_config` : string value to set the path to JSON configuration file for custom logging fields (default: disabled)
- `activity_log_prefix_headers` : comma-separated list of header prefixes to log (e.g., "X-Custom-,X-Trace-")
- `activity_log_regex_headers` : comma-separated list of regex patterns for headers to log
- `activity_log_exclude_headers` : comma-separated list of regex patterns for headers to exclude from logging
- `activity_log_mask_sensitive` : boolean value to mask sensitive header values (default: `false`)

Options set from the command line, override the ones set in the config file.

> **Note**: For advanced logging configuration and performance optimization, see our [Performance and Logging Guide](PERFORMANCE_AND_LOGGING.md).

##### littleproxy.properties Example

````properties
dnssec=true
transparent=false
idleConnectionTimeout=60
connect_timeout=30
max_initial_line_length=8192
max_header_size=16384
max_chunk_size=16384
name=LittleProxy
address=12.45.666.789:8080
port=8080
nic=eth0
proxy_alias=myproxy
allow_local_only=false
authenticate_ssl_clients=false
ssl_clients_trust_all_servers=false
ssl_clients_send_certs=false
ssl_clients_key_store_file_path=/path/to/keystore.jks
ssl_clients_key_store_alias=myalias
ssl_clients_key_store_password=mypassword
throttle_read_bytes_per_second=1024
throttle_write_bytes_per_second=1024
allow_requests_to_origin_server=true
allow_proxy_protocol=true
send_proxy_protocol=true
activity_log_format=CLF
````
#### DNSSec

This will start LittleProxy with DNSSEC validation enabled ; i.e, it will use secure DNS lookups for outbound
connections.


```bash
$ ./run.bash --dnssec true
```

#### Log configuration file

This will start LittleProxy with the specified log configuration file.
Path of the log configuration file can be relative or absolute. 

If it is relative, it will be resolved relative to the current working directory :
```bash
$ ./run.bash --log_config ./log4j.xml
```
If it is absolute, it will be resolved as is :

```bash
$ ./run.bash --log_config /home/user/log4j.xml
```

#### Activity Log Format

This will enable the activity tracker with the specified log format.
Supported formats: `KEYVALUE`, `CLF`, `ELF`, `W3C`, `JSON`, `LTSV`, `CSV`, `SQUID`, `HAPROXY`.

```bash
$ ./run.bash --activity_log_format CLF
```

**Note on INFO Level Logging:** When `--activity_log_level INFO` is used, the output format will match the configured `--activity_log_format`. For example, if you set `--activity_log_format JSON`, the INFO level logs will be in JSON format. If you set `--activity_log_format KEYVALUE` (or don't specify a format), the INFO level logs will use the traditional key=value format.

#### Advanced Logging Configuration

LittleProxy supports advanced logging configuration through JSON configuration files and CLI options. You can customize which headers to log, apply pattern matching, exclude sensitive headers, and mask values.

**JSON Configuration File:**

Create a JSON file to define custom logging fields:

```json
{
  "standardFields": ["TIMESTAMP", "CLIENT_IP", "METHOD", "URI", "STATUS"],
  "prefixHeaders": [
    {"prefix": "X-Custom-", "fieldNameTransformer": "lower_underscore"},
    {"prefix": "X-Trace-", "fieldNameTransformer": "remove_prefix"}
  ],
  "regexHeaders": [
    {"pattern": "X-.*-Id", "fieldNameTransformer": "lower_underscore"}
  ],
  "excludeHeaders": [
    {"pattern": "Authorization|Cookie", "valueTransformer": "mask_full"}
  ],
  "computedFields": ["GEOLOCATION_COUNTRY", "RESPONSE_TIME_CATEGORY"]
}
```

Use the configuration file:

```bash
$ ./run.bash --activity_log_format JSON --activity_log_field_config ./logging-config.json
```

**Inline CLI Options:**

For simple configurations, use CLI options directly:

```bash
# Log all headers starting with X-Custom- and X-Trace-
$ ./run.bash --activity_log_format JSON --activity_log_prefix_headers "X-Custom-,X-Trace-"

# Log headers matching regex patterns
$ ./run.bash --activity_log_format JSON --activity_log_regex_headers "X-.*-Id,X-Request-.*"

# Exclude sensitive headers
$ ./run.bash --activity_log_format JSON --activity_log_exclude_headers "Authorization,Cookie,X-API-Key"

# Combined example
$ ./run.bash --activity_log_format JSON \
  --activity_log_prefix_headers "X-Custom-" \
  --activity_log_exclude_headers "Authorization,Cookie"
```

**Hybrid Approach (File + CLI):**

You can combine a base configuration file with CLI overrides:

```bash
$ ./run.bash --activity_log_format JSON \
  --activity_log_field_config ./base-config.json \
  --activity_log_exclude_headers "X-Secret-Header"
```

**Field Name Transformers:**

- `lower_underscore`: Convert header names to lowercase with underscores (e.g., `X-Custom-Auth` → `x_custom_auth`)
- `remove_prefix`: Remove the X- prefix and convert to lowercase (e.g., `X-Trace-Id` → `trace_id`)
- `lower_hyphen`: Convert to lowercase with hyphens
- `upper_underscore`: Convert to uppercase with underscores

**Value Transformers:**

- `mask_sensitive`: Mask sensitive values showing first/last 4 chars (e.g., `secret-token-123` → `secr****t-123`)
- `mask_full`: Completely mask the value (e.g., `secret` → `****`)
- `mask_email`: Mask email addresses (e.g., `user@example.com` → `us***@example.com`)
- `truncate_100`: Truncate values to 100 characters

#### Port

This will start LittleProxy on port `8080` by default.
You can customize the port by passing a port number as an argument to the script :

```bash
$ ./run.bash --port 9090
```

#### NIC

This will start LittleProxy on the default network interface. You can customize the network interface by passing
a NIC name (`eth0` in the example below) as an argument to the script :

```bash
$ ./run.bash --nic eth0
```

#### MITM Manager

If you pass this option, this will start LittleProxy with the default MITM manager (`SelfSignedMitmManager` implementation).
It will generate a self-signed certificate for each domain you visit.

```bash
$ ./run.bash --mitm
```
#### name

This will start LittleProxy with the specified name. This name will be used to name the threads.

```bash
$ ./run.bash --name MyProxy
```

#### address

This will start LittleProxy binding to the specified address. IPV4,IPV6 and hostname addresses are supported.

```bash
$ ./run.bash --address 127.0.0.1:8080
```
#### nic

This will start LittleProxy binding to the specified network interface.

```bash
$ ./run.bash --nic eth0
```

#### proxy_alias

This will start LittleProxy with the specified proxy alias.
The alias or pseudonym for this proxy, used when adding the `Via` header.

```bash
$ ./run.bash --proxy_alias MyProxy
```

#### allow_local_only

This will start LittleProxy allowing only local connections (default is `false`).

```bash
$ ./run.bash --allow_local_only true
```

#### authenticate_ssl_clients

This will start LittleProxy authenticating SSL clients (default is `false`).

```bash
$ ./run.bash --authenticate_ssl_clients true```

#### trust_all_servers

This will start LittleProxy authenticating SSL clients and trusting all servers (default is `false`).

```bash
$ ./run.bash --authenticate_ssl_clients true --trust_all_servers true
```
#### send_certs

This will start LittleProxy authenticating SSL clients and sending certificates (default is `false`).

```bash
$ ./run.bash --authenticate_ssl_clients true --send_certs true```

#### ssl_client_keystore_path

This will start LittleProxy authenticating SSL clients and using the specified keystore path.

```bash
$ ./run.bash --authenticate_ssl_clients true --ssl_client_keystore_path /path/to/keystore`
```
#### ssl_client_keystore_alias

This will start LittleProxy authenticating SSL clients and using the specified keystore alias.

```bash
$ ./run.bash --authenticate_ssl_clients true --ssl_client_keystore_alias myalias```
```
#### ssl_client_keystore_password

This will start LittleProxy authenticating SSL clients and using the specified keystore password.

```bash
$ ./run.bash --authenticate_ssl_clients true --ssl_client_keystore_password mypassword```

#### throttle_read_bytes_per_second

This will start LittleProxy throttling the read bytes per second.

```bash
$ ./run.bash --throttle_read_bytes_per_second 1024
```

#### throttle_write_bytes_per_second

This will start LittleProxy throttling the write bytes per second.

```bash
$ ./run.bash --throttle_write_bytes_per_second 1024
```

#### allow_request_to_origin_server

This will start LittleProxy allowing requests to the origin server.

```bash
$ ./run.bash --allow_request_to_origin_server true
```

#### allow_proxy_protocol

This will start LittleProxy allowing the PROXY protocol.

```bash
$ ./run.bash --allow_proxy_protocol true
```

#### send_proxy_protocol

This will start LittleProxy sending the PROXY protocol header.

```bash
$ ./run.bash --send_proxy_protocol true
```

#### client_to_proxy_worker_threads

This will start LittleProxy with the specified number of client to proxy worker threads.

```bash
$ ./run.bash --client_to_proxy_worker_threads 10
```

#### proxy_to_server_worker_threads

This will start LittleProxy with the specified number of proxy to server worker threads.

```bash
$ ./run.bash --proxy_to_server_worker_threads 10
```

#### acceptor_threads

This will start LittleProxy with the specified number of acceptor threads.

```bash
$ ./run.bash --acceptor_threads 10
```


#### server

This will start LittleProxy as a server, i.e it will not stop, until you stop the process running it (via a `kill`kill command).

```bash
$ ./run.bash --server
```

#### Help

This will print the help message:

```bash 
$ ./run.bash --help
```

## Embedding in your own projects

You can embed LittleProxy in your own projects through Maven with the following :
```
    <dependency>
        <groupId>io.github.littleproxy</groupId>
        <artifactId>littleproxy</artifactId>
        <version>2.6.0</version>
    </dependency>
```

Or with Gradle like this

`implementation "io.github.littleproxy:littleproxy:2.6.0"`

Once you've included LittleProxy, you can start the server with the following:

```java
HttpProxyServer server =
        DefaultHttpProxyServer.bootstrap()
                .withPort(8080)
                .start();
```

To intercept and manipulate HTTPS traffic, LittleProxy uses a man-in-the-middle (MITM) manager. LittleProxy's default
implementation (`SelfSignedMitmManager`) has a fairly limited feature set. For greater control over certificate impersonation,
browser trust, the TLS handshake, and more, use a LittleProxy-compatible MITM extension:
- [LittleProxy-mitm](https://github.com/ganskef/LittleProxy-mitm) - A LittleProxy MITM extension that aims to support every Java platform including Android
- [mitm](https://github.com/lightbody/browsermob-proxy/tree/master/mitm) - A LittleProxy MITM extension that supports elliptic curve cryptography and custom trust stores

To filter HTTP traffic, you can add request and response filters using a
`HttpFiltersSource(Adapter)`, for example:

```java
HttpProxyServer server =
        DefaultHttpProxyServer.bootstrap()
                .withPort(8080)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                                // TODO: implement your filtering here
                                return null;
                            }

                            @Override
                            public HttpObject serverToProxyResponse(HttpObject httpObject) {
                                // TODO: implement your filtering here
                                return httpObject;
                            }
                        };
                    }
                })
                .start();
```

Please refer to the Javadoc of `org.littleshoot.proxy.HttpFilters` to see the
methods you can use.

To enable aggregator and inflater you have to return a value greater than 0 in
your `HttpFiltersSource#get(Request/Response)BufferSizeInBytes()` methods. This
provides to you a `FullHttp(Request/Response)` with the complete content in your
filter uncompressed. Otherwise, you have to handle the chunks yourself.

```java
    @Override
public int getMaximumResponseBufferSizeInBytes() {
    return 10 * 1024 * 1024;
}
```

This size limit applies to every connection. To disable aggregating by URL at
*.iso or *dmg files for example, you can return in your filters source a filter
like this:

```java
return new HttpFiltersAdapter(originalRequest, serverCtx) {
    @Override
    public void proxyToServerConnectionSucceeded(ChannelHandlerContext serverCtx) {
        ChannelPipeline pipeline = serverCtx.pipeline();
        if (pipeline.get("inflater") != null) {
            pipeline.remove("inflater");
        }
        if (pipeline.get("aggregator") != null) {
            pipeline.remove("aggregator");
        }
        super.proxyToServerConnectionSucceeded(serverCtx);
    }
};
```
This enables huge downloads in an application, which regular handles size
limited `FullHttpResponse`s to modify its content, HTML for example.

A proxy server like LittleProxy contains always a web server, too. If you get a
URI without scheme, host and port in `originalRequest` it's a direct request to
your proxy. You can return a `HttpFilters` implementation which answers
responses with HTML content or redirects in `clientToProxyRequest` like this:

```java
import java.nio.charset.StandardCharsets;

import static java.nio.charset.StandardCharsets.UTF_8;

public class AnswerRequestFilter extends HttpFiltersAdapter {
    private final String answer;

    public AnswerRequestFilter(HttpRequest originalRequest, String answer) {
        super(originalRequest, null);
        this.answer = answer;
    }

    @Override
    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
        ByteBuf buffer = Unpooled.wrappedBuffer(answer.getBytes(UTF_8));
        HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buffer);
        HttpHeaders.setContentLength(response, buffer.readableBytes());
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_TYPE, "text/html");
        return response;
    }
}
```
On answering a redirect, you should add a Connection: close header, to avoid
blocking behavior:
```java
		HttpHeaders.setHeader(response, Names.CONNECTION, Values.CLOSE);
```
With this trick, you can implement a UI to your application very easy.

If you want to create additional proxy servers with similar configuration but
listening on different ports, you can clone an existing server.  The cloned
servers will share event loops to reduce resource usage and when one clone is
stopped, all are stopped.

```java
existingServer.clone().withPort(8081).start()
```


### Logging Activity Tracker

LittleProxy includes an `ActivityLogger` that can log detailed information about each request and response handled by the proxy. It supports multiple standard log formats and customizable field configuration, which can be useful for integration with log analysis tools.

**Package:** `org.littleshoot.proxy.extras.logging`

**Basic Usage:**

```java
import org.littleshoot.proxy.extras.logging.ActivityLogger;
import org.littleshoot.proxy.extras.logging.LogFormat;

// ...

DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .plusActivityTracker(new ActivityLogger(LogFormat.CLF)) // Use Common Log Format
    .start();
```

**With Custom Field Configuration:**

```java
import org.littleshoot.proxy.extras.logging.ActivityLogger;
import org.littleshoot.proxy.extras.logging.LogFieldConfiguration;
import org.littleshoot.proxy.extras.logging.LogFormat;
import org.littleshoot.proxy.extras.logging.StandardField;
import org.littleshoot.proxy.extras.logging.ComputedField;

// ...

// Build custom field configuration
LogFieldConfiguration fieldConfig = LogFieldConfiguration.builder()
    .addStandardField(StandardField.TIMESTAMP)
    .addStandardField(StandardField.CLIENT_IP)
    .addStandardField(StandardField.METHOD)
    .addStandardField(StandardField.URI)
    .addStandardField(StandardField.STATUS)
    .addRequestHeadersWithPrefix("X-Custom-")              // Log all headers starting with X-Custom-
    .addRequestHeadersMatching("X-.*-Id")                  // Log headers matching regex
    .excludeRequestHeadersMatching("Authorization|Cookie") // Exclude sensitive headers
    .addComputedField(ComputedField.GEOLOCATION_COUNTRY)   // Add computed fields
    .build();

DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .plusActivityTracker(new ActivityLogger(LogFormat.JSON, fieldConfig))
    .start();
```

**Loading Configuration from JSON File:**

```java
import org.littleshoot.proxy.extras.logging.LogFieldConfigurationFactory;

// Load configuration from JSON file
LogFieldConfiguration fieldConfig = LogFieldConfigurationFactory.fromJsonFile("/path/to/config.json");

DefaultHttpProxyServer.bootstrap()
    .withPort(8080)
    .plusActivityTracker(new ActivityLogger(LogFormat.JSON, fieldConfig))
    .start();
```

#### Supported Log Formats

The `LogFormat` enum provides several standard formats:

*   **`CLF` (Common Log Format)**: The standard NCSA Common log format.
    *   Example: `127.0.0.1 - - [24/Dec/2025:00:00:00 +0000] "GET /index.html HTTP/1.1" 200 1234`
*   **`ELF` (Extended Log Format)**: Uses the NCSA Combined Log Format, which includes Referer and User-Agent.
    *   Example: `127.0.0.1 - - [date] "GET /..." 200 123 "http://referer" "Mozilla/5.0"`
*   **`W3C`**: A standard W3C Extended Log File Format (space-separated fields).
    *   Example: `2025-12-24 00:00:00 127.0.0.1 GET /index.html 200 1234 "Mozilla/5.0"`
*   **`JSON`**: Structured logging in JSON format, ideal for modern log aggregators (ELK, Splunk, etc.). Includes duration.
    *   Example: `{"timestamp":"...","client_ip":"127.0.0.1","method":"GET","duration":15,...}`
*   **`LTSV` (Labeled Tab-Separated Values)**: Efficient, human-readable, and machine-parsable format.
    *   Example: `time:2025-...\thost:127.0.0.1\tmethod:GET\t...`
*   **`CSV` (Comma-Separated Values)**: Standard CSV format for easy import into spreadsheets.
    *   Example: `"timestamp","127.0.0.1","GET",...`
*   **`SQUID`**: Squid native access log format. Useful for tools expecting Squid logs.
*   **`HAPROXY`**: A format mimicking HAProxy's HTTP logging, focusing on timing and status.

For examples of configuring logging, see [src/test/resources/log4j.xml](src/test/resources/log4j.xml).

#### Customizing Logging Configuration

You can customize the `log4j.xml` configuration to control how logs are output. This is particularly useful for separating access logs from system logs.

**1. Standard Output (Default)**

To print access logs to the console without standard Log4j prefixes (timestamps, thread names, etc.), use a specific appender for the tracker:

```xml
<appender class="org.apache.log4j.ConsoleAppender" name="AccessLogAppender">
    <layout class="org.apache.log4j.PatternLayout">
        <param value="%m%n" name="ConversionPattern"/>
    </layout>
</appender>

<logger additivity="false" name="org.littleshoot.proxy.extras.logging.ActivityLogger">
    <level value="info"/>
    <appender-ref ref="AccessLogAppender"/>
</logger>
```

**2. Dedicated Access Log File**

To write access logs to a separate file (e.g., `access.log`) and exclude them from the main log, use a `FileAppender`:

```xml
<appender class="org.apache.log4j.FileAppender" name="AccessLogFileAppender">
    <param value="access.log" name="File"/>
    <layout class="org.apache.log4j.PatternLayout">
        <param value="%m%n" name="ConversionPattern"/>
    </layout>
</appender>

<logger additivity="false" name="org.littleshoot.proxy.extras.logging.ActivityLogger">
    <level value="info"/>
    <appender-ref ref="AccessLogFileAppender"/>
</logger>
```

If you have questions, please visit our Google Group here:

https://groups.google.com/forum/#!forum/littleproxy2

(The original group at https://groups.google.com/forum/#!forum/littleproxy isn't
accepting posts from new users.  But it's still a great resource if you're
searching for older answers.)

To subscribe, send an e-mail to [LittleProxy2+subscribe@googlegroups.com](mailto:LittleProxy2+subscribe@googlegroups.com).

## Performance and Logging Guide

For comprehensive information on logging performance optimization, including:

- **Synchronous vs Asynchronous Logging**: Performance comparison and use cases
- **Activity Log Formats**: All supported formats (CLF, ELF, JSON, SQUID, W3C, LTSV, CSV, HAPROXY)
- **Log Filtering**: BurstFilter and custom filter implementations
- **Groovy Script Filters**: Dynamic filtering with sampling and conditional logic
- **Best Practices**: Production-ready recommendations and troubleshooting

Please see our **[Performance and Logging Guide](PERFORMANCE_AND_LOGGING.md)**.

Acknowledgments
---------------

Many thanks to [The Measurement Factory](http://www.measurement-factory.com/) for the
use of [Co-Advisor](http://coad.measurement-factory.com/) for HTTP standards
compliance testing. 
