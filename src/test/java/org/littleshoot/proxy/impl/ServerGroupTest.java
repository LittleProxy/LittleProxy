package org.littleshoot.proxy.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.HttpProxyServer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerGroupTest {
    private ServerGroup serverGroup;

    @Test
    void autoStop() {
        serverGroup = new ServerGroup("Test", 4, 4, 4);
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap().withServerGroup(serverGroup).start();
        proxyServer.stop();
        assertTrue(serverGroup.isStopped(), "server group stopped");
    }

    @Test
    void manualStop() {
        serverGroup = new ServerGroup("Test", 4, 4, 4, false);
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap().withServerGroup(serverGroup).start();
        proxyServer.stop();
        assertFalse(serverGroup.isStopped(), "server group stopped");
    }

    @AfterEach
    void shutdown() {
        if (serverGroup != null) {
            serverGroup.shutdown(false);
        }
    }
}
