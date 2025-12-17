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
$ ./run.bash --log-config ./log4j.xml
```
If it is absolute, it will be resolved as is :

```bash
$ ./run.bash --log-config /home/user/log4j.xml
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
$ ./run.bash --mitm-manager
```

### server

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
        <version>2.4.7</version>
    </dependency>
```

Or with Gradle like this

`implementation "io.github.littleproxy:littleproxy:2.4.7"`

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
