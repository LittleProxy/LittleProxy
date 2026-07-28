# LittleProxy Request Handling Architecture

This document explains with detailed diagrams how LittleProxy handles HTTP/HTTPS requests.

## Architecture Overview

```mermaid
flowchart TB
    subgraph Client["🖥️ Client (Browser)"]
        C[Client Application]
    end

    subgraph LittleProxy["🔧 LittleProxy Server"]
        direction TB
        
        subgraph ServerGroup["ServerGroup (Thread Pools)"]
            CAT["ClientToProxy Acceptor"]
            CWT["ClientToProxy Worker"]
            SWT["ProxyToServer Worker"]
        end
        
        subgraph ClientToProxy["ClientToProxyConnection"]
            CP_PIPELINE["HTTP Pipeline<br/>Decoder → Encoder"]
            CP_HANDLER["Main Handler"]
            AUTH["Authentication"]
        end
        
        subgraph ProxyToServer["ProxyToServerConnection"]
            PS_PIPELINE["HTTP Pipeline<br/>Encoder → Decoder"]
            PS_HANDLER["Main Handler"]
            CONN_FLOW["ConnectionFlow"]
        end
        
        subgraph Filters["🎛️ HttpFilters"]
            F1["clientToProxyRequest()"]
            F2["proxyToServerRequest()"]
            F3["serverToProxyResponse()"]
            F4["proxyToClientResponse()"]
        end
    end
    
    subgraph Upstream["🌐 Upstream"]
        direction TB
        CHAINED["Chained Proxy<br/>(Optional)"]
        TARGET["Target Server"]
    end
    
    C -->|"HTTP/HTTPS Request"| CAT
    CAT --> CWT
    CWT --> ClientToProxy
    CP_PIPELINE --> CP_HANDLER
    CP_HANDLER --> F1
    F1 -->|"Short-circuit?"| CP_HANDLER
    F1 -->|"Continue"| F2
    F2 --> ProxyToServer
    ProxyToServer --> CONN_FLOW
    CONN_FLOW -->|"Connect + SSL/SOCKS"| PS_PIPELINE
    PS_PIPELINE --> PS_HANDLER
    PS_HANDLER -->|"HTTP Proxy"| CHAINED
    PS_HANDLER -->|"Direct"| TARGET
    CHAINED --> TARGET
    TARGET -->|"Response"| PS_HANDLER
    PS_HANDLER --> F3
    F3 --> CP_HANDLER
    CP_HANDLER --> F4
    F4 -->|"Final Response"| C
```

## Main Class Diagram

```mermaid
classDiagram
    class ProxyConnection~I~ {
        <<abstract>>
        -ConnectionState currentState
        -boolean tunneling
        -SSLEngine sslEngine
        +read(Object msg)
        +write(Object msg)
        +connected()
        +disconnected()
        +encrypt(SSLEngine)
        +become(ConnectionState)
    }
    
    class ClientToProxyConnection {
        -Map~String,ProxyToServerConnection~ serverConnections
        -HttpFilters currentFilters
        -HttpRequest currentRequest
        -boolean mitming
        +readHTTPInitial(HttpRequest)
        +readHTTPChunk(HttpContent)
        +respond(ProxyToServerConnection, HttpFilters, HttpRequest, HttpResponse, HttpObject)
        +serverConnectionSucceeded()
        +serverConnectionFailed()
    }
    
    class ProxyToServerConnection {
        -ClientToProxyConnection clientConnection
        -ChainedProxy chainedProxy
        -ConnectionFlow connectionFlow
        -Queue~ChainedProxy~ availableChainedProxies
        -HttpRequest initialRequest
        +readHTTPInitial(HttpResponse)
        +write(Object, HttpFilters)
        +connectionSucceeded()
        +connectionFailed()
        +initializeConnectionFlow()
    }
    
    class ConnectionFlow {
        -Deque~ConnectionFlowStep~ steps
        -ConnectionFlowStep currentStep
        +start()
        +advance()
        +succeed()
        +fail()
    }
    
    class ConnectionFlowStep~T~ {
        <<abstract>>
        -ProxyConnection connection
        -ConnectionState state
        +execute() Future
        +onSuccess(ConnectionFlow)
        +read(ConnectionFlow, Object)
    }
    
    class DefaultHttpProxyServer {
        -ServerGroup serverGroup
        -HttpFiltersSource filtersSource
        -MitmManager mitmManager
        -ChainedProxyManager chainProxyManager
        -ProxyAuthenticator proxyAuthenticator
        +start()
        +stop()
    }
    
    class HttpFilters {
        <<interface>>
        +clientToProxyRequest(HttpObject)
        +proxyToServerRequest(HttpObject)
        +serverToProxyResponse(HttpObject)
        +proxyToClientResponse(HttpObject)
    }
    
    ProxyConnection <|-- ClientToProxyConnection
    ProxyConnection <|-- ProxyToServerConnection
    ClientToProxyConnection "1" --> "0..*" ProxyToServerConnection : manages
    ProxyToServerConnection --> ConnectionFlow : uses
    ConnectionFlow "1" --> "0..*" ConnectionFlowStep : contains
    ClientToProxyConnection --> HttpFilters : applies
    ProxyToServerConnection --> HttpFilters : applies
    DefaultHttpProxyServer --> ClientToProxyConnection : creates
    DefaultHttpProxyServer --> HttpFilters : provides
```

## HTTP Request Lifecycle (Non-CONNECT)

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant C2P as ClientToProxyConnection
    participant Filters as HttpFilters
    participant P2S as ProxyToServerConnection
    participant CF as ConnectionFlow
    participant Target as Target Server

    Note over Client,Target: Standard HTTP Request (GET/POST/...)
    
    Client->>C2P: HTTP Request
    activate C2P
    
    C2P->>Filters: clientToProxyRequest(request)
    alt Short-circuit response
        Filters-->>C2P: HttpResponse (short-circuit)
        C2P->>C2P: respondWithShortCircuitResponse()
        C2P-->>Client: Filtered Response
    else Continue processing
        Filters-->>C2P: null (continue)
        
        C2P->>C2P: identifyHostAndPort()
        C2P->>C2P: find/reuse ProxyToServerConnection
        
        C2P->>Filters: proxyToServerRequest(request)
        Filters-->>C2P: null (continue)
        
        C2P->>P2S: write(request, filters)
        activate P2S
        
        alt Existing connection
            P2S->>Target: Send request
        else New connection
            P2S->>CF: initializeConnectionFlow()
            CF->>CF: start() → ConnectChannel
            CF->>Target: TCP Connection
            CF->>CF: succeed()
            CF-->>P2S: connectionSucceeded()
            P2S->>Target: Send request
        end
        
        P2S->>Filters: proxyToServerRequestSending()
        P2S->>Filters: proxyToServerRequestSent()
        deactivate P2S
        
        Target-->>P2S: HTTP Response
        activate P2S
        P2S->>Filters: serverToProxyResponseReceiving()
        P2S->>Filters: serverToProxyResponse(response)
        Filters-->>P2S: modified response
        
        P2S->>C2P: respond(filters, request, response, object)
        deactivate P2S
        
        C2P->>Filters: proxyToClientResponse(response)
        Filters-->>C2P: final response
        C2P->>C2P: modifyResponseHeaders()
        C2P-->>Client: Final Response
        deactivate C2P
        
        P2S->>Filters: serverToProxyResponseReceived()
    end
```

## CONNECT Request Lifecycle (HTTPS Tunneling)

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant C2P as ClientToProxyConnection
    participant P2S as ProxyToServerConnection
    participant CF as ConnectionFlow
    participant Target as Target Server

    Note over Client,Target: HTTPS Tunneling (without MITM)
    
    Client->>C2P: CONNECT host:443
    activate C2P
    C2P->>C2P: doReadHTTPInitial()
    C2P->>P2S: create() + write(connectRequest)
    activate P2S
    
    P2S->>CF: initializeConnectionFlow()
    CF->>CF: start()
    
    Note right of CF: ConnectionFlow Steps
    CF->>Target: 1. ConnectChannel (TCP)
    CF->>Target: 2. StartTunneling (tunnel mode)
    
    CF-->>C2P: RespondCONNECTSuccessful
    C2P-->>Client: 200 Connection established
    
    CF->>C2P: StartTunneling
    CF->>CF: succeed()
    CF-->>P2S: connectionSucceeded()
    deactivate P2S
    
    Note over Client,Target: Active Tunnel - raw data
    
    Client->>C2P: Raw SSL/TLS data
    C2P->>P2S: readRaw() → write()
    P2S->>Target: Raw SSL/TLS data
    
    Target-->>P2S: Raw SSL/TLS data
    P2S->>C2P: readRaw() → write()
    C2P-->>Client: Raw SSL/TLS data
    
    deactivate C2P
```

## Connection Flow (with MITM)

```mermaid
flowchart TB
    subgraph "ConnectionFlow Steps"
        START(["Start"]) --> CONNECT["1️⃣ ConnectChannel<br/>TCP Connection"]
        CONNECT --> CHAIN_PROXY{"Chained Proxy ?"}
        
        CHAIN_PROXY -->|"Yes - HTTP"| HTTP_PROXY["HTTPCONNECTWithChainedProxy"]
        CHAIN_PROXY -->|"Yes - SOCKS4"| SOCKS4["SOCKS4CONNECTWithChainedProxy"]
        CHAIN_PROXY -->|"Yes - SOCKS5"| SOCKS5["SOCKS5InitialRequest<br/>→ SOCKS5SendPasswordCredentials<br/>→ SOCKS5CONNECTRequest"]
        CHAIN_PROXY -->|"No"| CHECK_CONNECT{"CONNECT Request ?"}
        
        HTTP_PROXY --> CHECK_CONNECT
        SOCKS4 --> CHECK_CONNECT
        SOCKS5 --> CHECK_CONNECT
        
        CHECK_CONNECT -->|"No"| END_SUCCESS(["Success<br/>AWAITING_INITIAL"])
        
        CHECK_CONNECT -->|"Yes"| MITM_CHECK{"MITM Enabled ?"}
        
        MITM_CHECK -->|"Yes"| ENCRYPT_SERVER["EncryptChannel<br/>(SSL to server)"]
        ENCRYPT_SERVER --> RESPOND_OK1["RespondCONNECTSuccessful<br/>200 to client"]
        RESPOND_OK1 --> ENCRYPT_CLIENT["MitmEncryptClientChannel<br/>(SSL to client)"]
        ENCRYPT_CLIENT --> END_MITM(["MITM Success<br/>AWAITING_INITIAL"])
        
        MITM_CHECK -->|"No"| TUNNEL_SERVER["StartTunneling<br/>(server side)"]
        TUNNEL_SERVER --> RESPOND_OK2["RespondCONNECTSuccessful<br/>200 to client"]
        RESPOND_OK2 --> TUNNEL_CLIENT["StartTunneling<br/>(client side)"]
        TUNNEL_CLIENT --> END_TUNNEL(["Tunnel Success<br/>Raw bytes mode"])
    end
    
    style START fill:#90EE90
    style END_SUCCESS fill:#90EE90
    style END_MITM fill:#FFD700
    style END_TUNNEL fill:#87CEEB
```

## Netty Pipeline - Client Side (Inbound)

```mermaid
flowchart LR
    subgraph "ClientToProxy Pipeline"
        direction LR
        IN["📥 Inbound"]
        OUT["📤 Outbound"]
        
        IN --> BYTES_READ["bytesReadMonitor<br/>📊 Read stats"]
        BYTES_READ --> BYTES_WRITE["bytesWrittenMonitor<br/>📊 Write stats"]
        BYTES_WRITE --> ENCODER["HttpResponseEncoder<br/>📤 Encode responses"]
        
        PROXY_DEC["HAProxyMessageDecoder<br/>🌐 Proxy Protocol"]
        DECODER["HttpRequestDecoder<br/>📥 Decode requests"]
        AGG["HttpObjectAggregator<br/>📦 Buffering (opt)"]
        REQ_MON["requestReadMonitor<br/>📊 Request stats"]
        RES_MON["responseWrittenMonitor<br/>📊 Response stats"]
        IDLE["IdleStateHandler<br/>⏱️ Timeout"]
        HANDLER["ClientToProxyConnection<br/>🎯 Main handler"]
        
        ENCODER --> PROXY_DEC
        PROXY_DEC --> DECODER
        DECODER --> AGG
        AGG --> REQ_MON
        REQ_MON --> RES_MON
        RES_MON --> IDLE
        IDLE --> HANDLER
        HANDLER --> OUT
    end
    
    style HANDLER fill:#FFD700,stroke:#FF8C00,stroke-width:3px
```

## Netty Pipeline - Server Side (Outbound)

```mermaid
flowchart LR
    subgraph "ProxyToServer Pipeline"
        direction LR
        IN["📥 Inbound"]
        OUT["📤 Outbound"]
        
        IN --> BYTES_READ["bytesReadMonitor<br/>📊 Read stats"]
        BYTES_READ --> BYTES_WRITE["bytesWrittenMonitor<br/>📊 Write stats"]
        BYTES_WRITE --> TRAFFIC["GlobalTrafficShapingHandler<br/>🚦 Throttling (opt)"]
        
        TRAFFIC --> PROXY_ENC["HAProxyMessageEncoder<br/>🌐 Proxy Protocol"]
        PROXY_ENC --> ENCODER["HttpRequestEncoder<br/>📤 Encode requests"]
        DECODER["HeadAwareHttpResponseDecoder<br/>📥 Decode responses"]
        AGG["HttpObjectAggregator<br/>📦 Buffering (opt)"]
        RES_MON["responseReadMonitor<br/>📊 Response stats"]
        REQ_MON["requestWrittenMonitor<br/>📊 Request stats"]
        IDLE["IdleStateHandler<br/>⏱️ Timeout"]
        HANDLER["ProxyToServerConnection<br/>🎯 Main handler"]
        
        ENCODER --> DECODER
        DECODER --> AGG
        AGG --> RES_MON
        RES_MON --> REQ_MON
        REQ_MON --> IDLE
        IDLE --> HANDLER
        HANDLER --> OUT
    end
    
    style HANDLER fill:#87CEEB,stroke:#4682B4,stroke-width:3px
```

## Connection State Machine

```mermaid
stateDiagram-v2
    [*] --> AWAITING_INITIAL: Connection accepted
    
    AWAITING_INITIAL --> AWAITING_CHUNK: Chunked request received
    AWAITING_CHUNK --> AWAITING_CHUNK: Chunk received
    AWAITING_CHUNK --> AWAITING_INITIAL: LastHttpContent received
    
    AWAITING_INITIAL --> CONNECTING: New server connection
    CONNECTING --> AWAITING_CONNECT_OK: TCP connection OK
    CONNECTING --> DISCONNECTED: Connection failed
    
    AWAITING_CONNECT_OK --> HANDSHAKING: SSL/SOCKS/CONNECT in progress
    AWAITING_CONNECT_OK --> AWAITING_INITIAL: Connect OK (no TLS)
    
    HANDSHAKING --> AWAITING_INITIAL: SSL handshake succeeded
    HANDSHAKING --> DISCONNECTED: Handshake failed
    
    AWAITING_INITIAL --> NEGOTIATING_CONNECT: CONNECT request received
    NEGOTIATING_CONNECT --> AWAITING_INITIAL: Tunnel established
    
    AWAITING_INITIAL --> AWAITING_PROXY_AUTHENTICATION: Auth required
    AWAITING_PROXY_AUTHENTICATION --> AWAITING_INITIAL: Auth succeeded
    AWAITING_PROXY_AUTHENTICATION --> DISCONNECT_REQUESTED: Auth failed
    
    AWAITING_INITIAL --> DISCONNECT_REQUESTED: Close requested
    AWAITING_CHUNK --> DISCONNECT_REQUESTED: Close requested
    
    DISCONNECT_REQUESTED --> DISCONNECTED: Disconnect
    DISCONNECTED --> [*]: End
```

## Filter Chain (HttpFilters)

```mermaid
flowchart TB
    subgraph "Filter Chain Execution Order"
        direction TB
        
        REQ["📝 Client Request"] --> F1["1. clientToProxyRequest()<br/>📍 Initial interception<br/>Short-circuit possible"]
        
        F1 -->|"Short-circuit"| RESP_FINAL["🔚 Client Response"]
        F1 -->|"Continue"| F2["2. proxyToServerRequest()<br/>📍 Modify before sending to server<br/>Short-circuit possible"]
        
        F2 -->|"Short-circuit"| RESP_FINAL
        F2 -->|"Continue"| CONN["🔌 Connect to Server"]
        
        CONN --> F3["3. serverToProxyResponse()<br/>📍 Modify server response<br/>Return null = disconnect"]
        
        F3 -->|"null (force disconnect)"| DISCONNECT["❌ Force disconnect"]
        F3 -->|"Continue"| F4["4. proxyToClientResponse()<br/>📍 Final response modification<br/>Return null = disconnect"]
        
        F4 -->|"null (force disconnect)"| DISCONNECT
        F4 -->|"Continue"| RESP_FINAL
    end
    
    subgraph "Callback Notifications (Order)"
        direction TB
        N1["clientToProxyRequest"] --> N2["proxyToServerConnectionQueued"]
        N2 --> N3["proxyToServerResolutionStarted"]
        N3 --> N4["proxyToServerResolutionSucceeded/Failed"]
        N4 --> N5["proxyToServerRequest"]
        N5 --> N6["proxyToServerConnectionStarted"]
        N6 --> N7["proxyToServerConnectionSSLHandshakeStarted (if HTTPS)"]
        N7 --> N8["proxyToServerConnectionSucceeded/Failed"]
        N8 --> N9["proxyToServerRequestSending"]
        N9 --> N10["proxyToServerRequestSent"]
        N10 --> N11["serverToProxyResponseReceiving"]
        N11 --> N12["serverToProxyResponse"]
        N12 --> N13["serverToProxyResponseReceived"]
        N13 --> N14["proxyToClientResponse"]
    end
    
    style F1 fill:#FFD700
    style F2 fill:#FFD700
    style F3 fill:#87CEEB
    style F4 fill:#87CEEB
```

## Chained Proxy Management

```mermaid
flowchart TB
    subgraph "ChainedProxy Resolution"
        START(["New request"]) --> RESOLVE["ChainedProxyManager.lookupChainedProxies()"]
        
        RESOLVE --> HAS_PROXY{"Proxies<br/>available ?"}
        HAS_PROXY -->|"No"| NULL["Returns null<br/>502 Bad Gateway"]
        HAS_PROXY -->|"Yes"| QUEUE["Proxy queue<br/>ConcurrentLinkedQueue"]
        
        QUEUE --> CREATE["Create ProxyToServerConnection<br/>with first proxy"]
        CREATE --> CONNECT["Attempt connection"]
        
        CONNECT --> SUCCESS{"Connection<br/>OK ?"}
        SUCCESS -->|"Yes"| USE["Use this proxy"]
        SUCCESS -->|"No"| FALLBACK["ChainedProxy.connectionFailed()"]
        
        FALLBACK --> NEXT_PROXY{"More proxies<br/>in queue ?"}
        NEXT_PROXY -->|"Yes"| NEXT["Take next proxy"]
        NEXT --> CONNECT
        
        NEXT_PROXY -->|"No"| FALLBACK_DIRECT["FALLBACK_TO_DIRECT_CONNECTION"]
        FALLBACK_DIRECT --> DIRECT["Direct connection<br/>(no proxy)"]
        DIRECT --> DIRECT_SUCCESS{"Direct<br/>OK ?"}
        DIRECT_SUCCESS -->|"Yes"| USE
        DIRECT_SUCCESS -->|"No"| FAIL["502 Bad Gateway"]
    end
    
    subgraph "Proxy Types"
        HTTP["HTTP Proxy<br/>→ CONNECT request"]
        SOCKS4["SOCKS4 Proxy<br/>→ CONNECT command"]
        SOCKS5["SOCKS5 Proxy<br/>→ Auth + CONNECT command"]
    end
    
    style USE fill:#90EE90
    style FAIL fill:#FF6B6B
    style NULL fill:#FF6B6B
```

## Backpressure Management

```mermaid
flowchart TB
    subgraph "Backpressure Management"
        C2P_SAT["ClientToProxy saturated"] --> STOP_ALL["Stop reading ALL<br/>ProxyToServer connections"]
        
        P2S_SAT["ProxyToServer saturated"] --> STOP_CLIENT["Stop reading<br/>ClientToProxy"]
        
        C2P_WRITE["ClientToProxy writable"] --> CHECK_ALL{"All servers<br/>writable ?"}
        CHECK_ALL -->|"Yes"| RESUME_CLIENT["Resume reading<br/>ClientToProxy"]
        CHECK_ALL -->|"No"| WAIT["Wait"]
        
        P2S_WRITE["ProxyToServer writable"] --> CHECK_SAT{"All servers<br/>non-saturated ?"}
        CHECK_SAT -->|"Yes"| RESUME_ALL["Resume reading ALL<br/>ProxyToServer + Client"]
        CHECK_SAT -->|"No"| KEEP_WAIT["Keep waiting"]
    end
    
    style STOP_ALL fill:#FFD700
    style STOP_CLIENT fill:#FFD700
    style RESUME_CLIENT fill:#90EE90
    style RESUME_ALL fill:#90EE90
```

## Key Components Summary

| Component | Role | File |
|-----------|------|------|
| **DefaultHttpProxyServer** | Entry point, bootstrap, configuration | `DefaultHttpProxyServer.java` |
| **ClientToProxyConnection** | Manages incoming client connections | `ClientToProxyConnection.java` |
| **ProxyToServerConnection** | Manages outgoing server connections | `ProxyToServerConnection.java` |
| **ConnectionFlow** | Orchestration of connection steps | `ConnectionFlow.java` |
| **ConnectionFlowStep** | Individual step in the connection flow | `ConnectionFlowStep.java` |
| **HttpFilters** | Interface for filtering/modifying requests/responses | `HttpFilters.java` |
| **ProxyConnection** | Abstract base class for connections | `ProxyConnection.java` |
| **ServerGroup** | Netty thread pool management | `ServerGroup.java` |

## Key Architecture Points

1. **Separation of Concerns**: The [`ClientToProxyConnection`](src/main/java/org/littleshoot/proxy/impl/ClientToProxyConnection.java:88) class manages the client side, while [`ProxyToServerConnection`](src/main/java/org/littleshoot/proxy/impl/ProxyToServerConnection.java:104) manages the server side.

2. **Connection Reuse**: Only one [`ProxyToServerConnection`](src/main/java/org/littleshoot/proxy/impl/ProxyToServerConnection.java:104) per host:port is maintained and reused for HTTP requests.

3. **Tunnel Mode**: For CONNECT requests (HTTPS), HTTP encoders/decoders are removed and data passes through in raw bytes mode.

4. **MITM (Man-In-The-Middle)**: Allows decrypting HTTPS traffic by acting as an SSL server on the client side and SSL client on the server side.

5. **Chained Proxies**: Support for HTTP, SOCKS4, and SOCKS5 with automatic fallback mechanism.

6. **Filters**: Extensible filter chain allowing modification of requests/responses at different stages.

7. **Backpressure**: Saturation management mechanism to prevent memory overload.
