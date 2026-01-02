package org.littleshoot.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.littleshoot.proxy.test.SocketUtil.getRandomAvailablePort;

/**
 * Unit tests for the Launcher class, specifically testing the start method.
 */
class LauncherTest {

    /**
     * Test that the start method handles the help option correctly.
     * Should print help and exit gracefully without starting the server.
     */
    @Test
    void testStartWithHelpOption() {
        // Given
        String[] args = {"--help"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles valid port option.
     */
    @Test
    void testStartWithValidPortOption() {
        int avaialablePort = getRandomAvailablePort();
        // Given
        String[] args = {"--port", ""+avaialablePort};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles invalid port option gracefully.
     */
    @Test
    void testStartWithInvalidPortOption() {
        // Given
        String[] args = {"--port", "invalid"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception and handle gracefully
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles MITM option.
     */
    @Test
    void testStartWithMitmOption() {
        // Given
        String[] args = {"--port", "9094", "--mitm"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles DNSSEC option.
     */
    @Test
    void testStartWithDnssecOption() {
        // Given
        String[] args = {"--port", "9098", "--dnssec", "true"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles invalid DNSSEC option gracefully.
     */
    @Test
    void testStartWithInvalidDnssecOption() {
        // Given
        String[] args = {"--dnssec", "invalid"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception and handle gracefully
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles config file option.
     */
    @Test
    void testStartWithConfigOption() {
        // Given
        String[] args = {"--port", "9097", "--config", "src/test/resources/littleproxy.properties"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles throttling options.
     */
    @Test
    void testStartWithThrottlingOptions() {
        // Given
        String[] args = {"--port", "9095", "--throttle_read_bytes_per_second", "1000", "--throttle_write_bytes_per_second", "2000"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles activity log format option.
     */
    @Test
    void testStartWithActivityLogFormat() {
        // Given
        String[] args = {"--port", "9093", "--activity_log_format", "CLF"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles invalid activity log format gracefully.
     */
    @Test
    void testStartWithInvalidActivityLogFormat() {
        // Given
        String[] args = {"--activity_log_format", "INVALID"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception and handle gracefully
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method works with no arguments (default configuration).
     */
    @Test
    void testStartWithNoArgs() {
        // Given
        String[] args = {"--port", "9096"};
        Launcher launcher = new Launcher();

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> launcher.start(args));
    }

    /**
     * Test that the start method handles extra/unrecognized arguments by throwing exception.
     */
    @Test
    void testStartWithExtraArguments() {
        // Given
        String[] args = {"extra", "arguments"};
        Launcher launcher = new Launcher();

        // When/Then - should throw exception for unrecognized arguments
        assertThrows(IllegalStateException.class, () -> launcher.start(args));
    }

    /**
     * Test that the start method handles server mode (though it will hang, so we test in separate thread).
     */
    @Test
    void testStartWithServerOption() throws InterruptedException {
        // Given
        String[] args = {"--server"};
        Launcher launcher = new Launcher();

        // When - run in separate thread to avoid hanging
        Thread testThread = new Thread(() -> {
            try {
                launcher.start(args);
            } catch (Exception e) {
                // Expected due to interrupt
            }
        });
        testThread.start();
        Thread.sleep(100); // Give it a moment to start
        testThread.interrupt();
        testThread.join(1000);

        // Then - thread should terminate
        assertSame(Thread.State.TERMINATED, testThread.getState());
    }


}