package org.littleshoot.proxy.extras;

import io.netty.handler.codec.http.HttpRequest;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public final class SelfSignedMitmManagerTest {

    @Test
    public void testServerSslEnginePeerAndPort() {
        String peer = "localhost";
        int port = 8090;
        SelfSignedSslEngineSource source = mock();
        SelfSignedMitmManager manager = new SelfSignedMitmManager(source);
        SSLEngine engine = mock();
        when(source.newSslEngine(peer, port)).thenReturn(engine);
        assertThat(manager.serverSslEngine(peer, port)).isEqualTo(engine);
    }

    @Test
    public void testServerSslEngine() {
        SelfSignedSslEngineSource source = mock();
        SelfSignedMitmManager manager = new SelfSignedMitmManager(source);
        SSLEngine engine = mock();
        when(source.newSslEngine()).thenReturn(engine);
        assertThat(manager.serverSslEngine()).isEqualTo(engine);
    }

    @Test
    public void testClientSslEngineFor() {
        HttpRequest request = mock();
        SSLSession session = mock();
        SelfSignedSslEngineSource source = mock();
        SelfSignedMitmManager manager = new SelfSignedMitmManager(source);
        SSLEngine engine = mock();
        when(source.newSslEngine()).thenReturn(engine);
        assertThat(manager.clientSslEngineFor(request, session)).isEqualTo(engine);
        verifyNoMoreInteractions(request, session);
    }
}
