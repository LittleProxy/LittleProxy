# LittleProxy Timing Metrics

This document provides a comprehensive overview of the timing metrics available in LittleProxy. These metrics are essential for monitoring performance, identifying bottlenecks, and understanding the complete request-response lifecycle.

## Timing Architecture Overview

LittleProxy tracks timing data throughout the lifecycle of both client-to-proxy and proxy-to-server interactions. These metrics are stored in the `FlowContext` and can be accessed programmatically or included in logs.

When running with `--activity_log_level DEBUG` (or higher) and `--activity_timing_mode ALL`, lifecycle events (`client_connected`, `request_received`, `server_connected`, `client_disconnected`, etc.) automatically include relevant timing attributes. Examples:

- `connection_age_ms`: age of the client connection at the moment of the event.
- `time_since_previous_request_ms`: time between consecutive requests on the same connection.
- `time_since_server_connect_ms`: elapsed time since the proxy connected to the upstream server.
- `server_connect_latency_ms`: how long it took from client connect to successful server connect.
- `tcp_client_connection_duration_ms` / `tcp_server_connection_duration_ms`: total lifetime of TCP connections at disconnect time.

These lifecycle timing fields are emitted automatically when timing mode is enabled; no additional configuration is required. DNS resolution now records `dns_resolution_start_time_ms` and `dns_resolution_end_time_ms` (internal) while exposing only `dns_resolution_time_ms` in lifecycle logs (e.g., `server_connected`) when timing mode is set to ALL.

### Request Lifecycle Flow

```text
   Client                  Proxy                   Server
     |                       |                       |
(1)  |--- TCP Connect ------>|                       |
     |                       |                       |
(2)  |--- SSL Handshake ---->|                       |
     |                       |                       |
(3)  |--- HTTP Request ----->|                       |
     |                       |                       |
(4)  |                       |--- TCP Connect ------>|
     |                       |                       |
(5)  |                       |--- HTTP Request ----->|
     |                       |                       |
(6)  |                       |<-- HTTP Response -----|
     |                       |                       |
(7)  |<-- HTTP Response -----|                       |
     |                       |                       |
(8)  |--- TCP Disconnect --->|                       |
     |                       |--- TCP Disconnect --->|
```

---

## Individual Timing Metrics

### 1. `http_request_processing_time_ms`

The total duration of a single HTTP request-response cycle as seen by the proxy.

*   **Definition**: Total time spent processing an HTTP request.
*   **Starts**: When the proxy receives the first byte of the HTTP request from the client.
*   **Ends**: When the proxy finishes sending the last byte of the HTTP response to the client.
*   **What's Included**: 
    *   Internal proxy processing (filters, routing).
    *   DNS resolution for the origin server.
    *   TCP connection establishment to the server (if a new connection is required).
    *   Time spent waiting for the server to respond.
    *   Data transfer time from server to proxy and proxy to client.

**Timeline Diagram:**
```text
Request Start                                     Response End
      |                                                |
      v <----------- http_request_processing_time_ms ----> v
      +------------------------------------------------+
      |  Receive  |  Proxy  |  Server  |  Receive |  Send  |
      |  Request  |  Logic  |  Wait    |  Data    |  Data  |
      +------------------------------------------------+
```

---

### 2. `response_latency_ms`

Time to First Byte (TTFB) - the time from when the proxy receives the request to when the first response byte arrives from the server.

*   **Definition**: Time to first byte from the upstream server (TTFB).
*   **Starts**: When the proxy receives the HTTP request from the client.
*   **Ends**: When the proxy receives the first byte of the HTTP response from the server.
*   **What's Included**:
    *   Internal proxy processing (filters, routing).
    *   DNS resolution for the origin server.
    *   TCP connection establishment to the server (if required).
    *   Server processing time to generate the response.
    *   Network latency to the server.
*   **Not Available For**: CONNECT requests (HTTPS tunneling). For CONNECT, this field will be `-` because the proxy tunnels encrypted data and cannot observe HTTP responses.

**Timeline Diagram:**
```text
Request Start                              First Byte from Server
      |                                                |
      v <-------------- response_latency_ms -----------> v
      +------------------------------------------------+
      |  Receive  |  Proxy  |  Server  |  Network      |
      |  Request  |  Logic  |  Process |  Transfer     |
      +------------------------------------------------+
```

---

### 3. `response_transfer_time_ms`

Time to transfer the complete response body from first byte to last byte sent to client.

*   **Definition**: Time to transfer the full response body.
*   **Starts**: When the proxy receives the first byte of the HTTP response from the server.
*   **Ends**: When the proxy finishes sending the last byte of the HTTP response to the client.
*   **What's Included**:
    *   Data transfer from server to proxy.
    *   Proxy processing (filtering, transformation).
    *   Data transfer from proxy to client.
*   **Not Available For**: CONNECT requests (HTTPS tunneling). For CONNECT, this field will be `-` because the proxy tunnels encrypted data and cannot observe HTTP responses.

**Relationship**: `http_request_processing_time_ms = response_latency_ms + response_transfer_time_ms`

**Timeline Diagram:**
```text
First Byte from Server                    Response End
      |                                        |
      v <----- response_transfer_time_ms -----> v
      +----------------------------------------+
      |  Receive Body  |  Process  |  Send     |
      |  from Server   |           |  to Client|
      +----------------------------------------+
```

---

### 4. `tcp_connection_establishment_time_ms`

The time taken specifically to establish a network connection to the upstream server.

*   **Definition**: TCP connection setup time to the origin server.
*   **Starts**: When the proxy initiates the TCP connection (SYN).
*   **Ends**: When the TCP connection is successfully established (ACK received).
*   **What's Included**: 
    *   Network round-trip time (RTT) to the server.
    *   Server's TCP stack processing time.

**Timeline Diagram:**
```text
Connect Call                                      Connected
      |                                                |
      v <--- tcp_connection_establishment_time_ms ---> v
      +---------------------------------------------+
      |           TCP 3-Way Handshake               |
      +---------------------------------------------+
```

---

### 5. `tcp_client_connection_duration_ms`

The total lifetime of the client's connection to the proxy.

*   **Definition**: Total client TCP connection lifetime.
*   **Starts**: When the client first connects to the proxy.
*   **Ends**: When the client's socket is closed.
*   **What's Included**: 
    *   Initial connection setup and SSL handshake.
    *   All HTTP requests processed over this connection (if using Keep-Alive).
    *   Idle time between requests.

**Timeline Diagram:**
```text
Client Connect                                  Client Disconnect
      |                                                |
      v <--------- tcp_client_connection_duration_ms ----> v
      +------------------------------------------------+
      | Connect | Request 1 | Idle | Request 2 | Close |
      +------------------------------------------------+
```

---

### 6. `tcp_server_connection_duration_ms`

The total lifetime of the proxy's connection to the upstream server.

*   **Definition**: Total server TCP connection duration.
*   **Starts**: When the proxy connects to the server.
*   **Ends**: When the proxy disconnects from the server.
*   **What's Included**: 
    *   TCP handshake and any upstream SSL handshake.
    *   All requests forwarded to this server over this connection.
    *   Idle time while the connection is pooled.

**Timeline Diagram:**
```text
Proxy Connect                                   Proxy Disconnect
      |                                                |
      v <--------- tcp_server_connection_duration_ms ----> v
      +------------------------------------------------+
      | Connect | Request 1 | Idle | Request 2 | Close |
      +------------------------------------------------+
```

---

### 7. `ssl_handshake_time_ms`

The duration of the SSL/TLS handshake between the client and the proxy.

*   **Definition**: SSL/TLS handshake duration.
*   **Starts**: When the proxy begins the SSL/TLS negotiation with the client.
*   **Ends**: When the handshake is successfully completed.
*   **What's Included**: 
    *   Cipher suite negotiation.
    *   Certificate exchange and validation.
    *   Key exchange (Diffie-Hellman, etc.).

**Timeline Diagram:**
```text
Handshake Start                                Handshake Success
      |                                                |
      v <----------- ssl_handshake_time_ms -------------> v
      +------------------------------------------------+
      | ClientHello | ServerHello | KeyEx | Finished   |
      +------------------------------------------------+
```

---

## Timing Relationships

Understanding how these metrics overlap is crucial for debugging:

1. **Response Time Decomposition**: `http_request_processing_time_ms = response_latency_ms + response_transfer_time_ms`. High latency indicates slow server processing; high transfer time indicates large payloads or slow network.
2. **Request vs. Connection**: `http_request_processing_time_ms` is usually a subset of `tcp_client_connection_duration_ms`. However, if the connection is reused for multiple requests, the connection duration will span across multiple request processing times.
3. **Server Connect vs. Request**: If the proxy has a pooled connection to the server, `tcp_connection_establishment_time_ms` will be **0** (or not present) for subsequent requests, significantly reducing the `response_latency_ms`.
4. **SSL vs. Request**: `ssl_handshake_time_ms` happens before the first HTTP request on a connection. It is included in `tcp_client_connection_duration_ms` but **not** in `http_request_processing_time_ms`.

---

## Visual Flow Diagram

This diagram shows the complete request lifecycle with all timings mapped:

```text
EVENT                          TIMING METRIC
 -------------------------------------------------------------------------------
 Client Connect                 [tcp_client_connection_duration_ms starts]
   |
   |--- TCP Handshake ---       [tcp_connection_establishment_time_ms (Client side)]
   |
 SSL Handshake Started          [ssl_handshake_time_ms starts]
   |
 SSL Handshake Finished         [ssl_handshake_time_ms ends]
   |
 HTTP Request Received          [http_request_processing_time_ms starts]
   |
   |--- DNS Resolution ---
   |
   |--- Server Connect ---      [tcp_connection_establishment_time_ms starts]
   |                            [tcp_server_connection_duration_ms starts]
   |
   |--- Server Connect OK -     [tcp_connection_establishment_time_ms ends]
   |
   |--- Server Request ---
   |
   |--- Server Response ---
   |
 HTTP Response Sent             [http_request_processing_time_ms ends]
   |
 (Idle / More Requests)
   |
 Client Disconnect              [tcp_client_connection_duration_ms ends]
 Server Disconnect              [tcp_server_connection_duration_ms ends]
```

---

## Configuration Examples

### CLI Configuration
You can configure which timing metrics are logged using the `--activity_timing_mode` option:

```bash
# Minimal timing - only HTTP request processing time (default)
java -jar littleproxy.jar --activity_timing_mode MINIMAL

# All timing metrics - includes TCP connection, SSL handshake, etc.
java -jar littleproxy.jar --activity_timing_mode ALL

# Disable timing metrics completely
java -jar littleproxy.jar --activity_timing_mode OFF
```

**Timing Mode Options:**

| Mode | Description | Timing Fields Included                                                                                                                                                                          |
|------|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `OFF` | No timing data | None                                                                                                                                                                                            |
| `MINIMAL` | Basic timing only (default) | `http_request_processing_time_ms`                                                                                                                                                               |
| `ALL` | Complete timing breakdown | All timing fields: `http_request_processing_time_ms`, `response_latency_ms`, `response_transfer_time_ms`, `tcp_connection_establishment_time_ms`, `tcp_client_connection_duration_ms`, `tcp_server_connection_duration_ms`, `ssl_handshake_time_ms`, `dns_resolution_time_ms` |

### Programmatic Access
Timing data is stored in the `FlowContext`. You can access it within your `HttpFilters` or an `ActivityTracker`:

```java
@Override
public void responseSentToClient(FlowContext flowContext, HttpResponse httpResponse) {
    Long requestTime = flowContext.getTimingData("http_request_processing_time_ms");
    Long sslTime = flowContext.getTimingData("ssl_handshake_time_ms");
    
    System.out.println("Request took: " + requestTime + "ms");
    if (sslTime != null) {
        System.out.println("SSL Handshake took: " + sslTime + "ms");
    }
}
```

## Best Practices

1. **Monitor `response_latency_ms` (TTFB)**: Time to First Byte measures how quickly the server starts responding. High latency indicates slow backend processing or network issues.
2. **Analyze `response_transfer_time_ms`**: Large transfer times relative to latency indicate large response bodies or slow client connections.
3. **Understand the relationship**: `http_request_processing_time_ms = response_latency_ms + response_transfer_time_ms`. If latency is low but processing time is high, the issue is transfer time.
4. **Monitor `tcp_connection_establishment_time_ms`**: High values here indicate network congestion between the proxy and the origin server or DNS resolution issues.
5. **Compare `http_request_processing_time_ms` with Server Logs**: If the proxy's processing time is significantly higher than the origin server's logged response time, the bottleneck is likely in the proxy's filters or the network between proxy and server.
6. **Use Connection Pooling**: Reusing server connections (keeping `tcp_server_connection_duration_ms` high) avoids the overhead of `tcp_connection_establishment_time_ms` for every request.
7. **Analyze `ssl_handshake_time_ms`**: If this is high, consider optimizing your SSL/TLS configuration (e.g., enabling session resumption or using faster cipher suites).

---

## Timing Metrics Availability by Request Type

### HTTP Requests (GET, POST, PUT, DELETE, PATCH, etc.)

All timing metrics are available for standard HTTP requests:

| Metric | Available | Notes |
|--------|-----------|-------|
| `http_request_processing_time_ms` | ✅ | Full request processing time |
| `response_latency_ms` | ✅ | Time to first byte from server |
| `response_transfer_time_ms` | ✅ | Time to transfer response body |
| `tcp_connection_establishment_time_ms` | ✅ | Connection setup time |
| `tcp_client_connection_duration_ms` | ✅ | Client connection lifetime |
| `tcp_server_connection_duration_ms` | ✅ | Server connection lifetime |
| `ssl_handshake_time_ms` | ✅ | SSL handshake duration (if HTTPS) |
| `dns_resolution_time_ms` | ✅ | DNS lookup time |

### CONNECT Requests (HTTPS Tunneling)

For CONNECT requests, the proxy establishes an encrypted tunnel and passes data through without inspecting it:

| Metric | Available | Notes |
|--------|-----------|-------|
| `http_request_processing_time_ms` | ✅ | **Tunnel establishment time only** (CONNECT → 200 OK) |
| `response_latency_ms` | ❌ | Shows `-` - proxy cannot see HTTP inside tunnel |
| `response_transfer_time_ms` | ❌ | Shows `-` - proxy cannot see HTTP inside tunnel |
| `tcp_connection_establishment_time_ms` | ✅ | Time to connect to target server |
| `tcp_client_connection_duration_ms` | ✅ | Client connection lifetime |
| `tcp_server_connection_duration_ms` | ✅ | Server connection lifetime |
| `ssl_handshake_time_ms` | ✅ | SSL handshake to target server |
| `dns_resolution_time_ms` | ✅ | DNS lookup time |

**Why latency/transfer metrics are unavailable for CONNECT:**

When a client sends `CONNECT example.com:443 HTTP/1.1`, the proxy:
1. Establishes a TCP connection to `example.com:443`
2. Sends `HTTP/1.1 200 Connection Established` back to the client
3. Switches to **tunnel mode** - all subsequent data is encrypted and passed through as-is

From this point, the proxy cannot observe HTTP request/response cycles because they're encrypted end-to-end between client and server. The `http_request_processing_time_ms` for a CONNECT request measures only the tunnel establishment time (step 1-2 above).
