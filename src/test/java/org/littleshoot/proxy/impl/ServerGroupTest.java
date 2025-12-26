package org.littleshoot.proxy.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HttpProxyServer;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.littleshoot.proxy.impl.ProxyUtils.getFreePort;

class ServerGroupTest {
    private ServerGroup serverGroup;

    private void startAndStopProxyServer() {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap().withPort(getFreePort()).withServerGroup(serverGroup).start();
        proxyServer.stop();
    }

    @Test
    void autoStop() {
        serverGroup = new ServerGroup("Test", 4, 4, 4);
        startAndStopProxyServer();
        assertTrue(serverGroup.isStopped(), "serverGroup.isStopped");
        assertThrows(IllegalStateException.class, this::startAndStopProxyServer);
    }

    @Test
    void manualStop() {
        serverGroup = new ServerGroup("Test", 4, 4, 4, false);
        startAndStopProxyServer();
        assertFalse(serverGroup.isStopped(), "serverGroup.isStopped");
        startAndStopProxyServer();
    }

    @AfterEach
    void shutdown() {
        if (serverGroup != null) {
            serverGroup.shutdown(false);
        }
    }
}
