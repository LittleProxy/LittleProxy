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
- 
Options set from the command line, override the ones set in the config file.

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
        <version>2.4.8</version>
    </dependency>
```

Or with Gradle like this

`implementation "io.github.littleproxy:littleproxy:2.4.8"`

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

For examples of configuring logging, see [src/test/resources/log4j.xml](src/test/resources/log4j.xml).

If you have questions, please visit our Google Group here:

https://groups.google.com/forum/#!forum/littleproxy2

(The original group at https://groups.google.com/forum/#!forum/littleproxy isn't
accepting posts from new users.  But it's still a great resource if you're
searching for older answers.)

To subscribe, send an e-mail to [LittleProxy2+subscribe@googlegroups.com](mailto:LittleProxy2+subscribe@googlegroups.com).

Acknowledgments
---------------

Many thanks to [The Measurement Factory](http://www.measurement-factory.com/) for the
use of [Co-Advisor](http://coad.measurement-factory.com/) for HTTP standards
compliance testing. 
