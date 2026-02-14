package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test for issue #439: ClientToProxyConnection.timedOut() bug.
 *
 * <p>The bug: When a server connection has been created but has never read any data (lastReadTime
 * == 0), the idle timeout check incorrectly prevents the client connection from being closed.
 *
 * <p>Original buggy code in ClientToProxyConnection.timedOut():
 *
 * <pre>
 * if (currentServerConnection == null || lastReadTime <= currentServerConnection.lastReadTime) {
 *     super.timedOut();
 * }
 * </pre>
 *
 * <p>When currentServerConnection.lastReadTime == 0 and client.lastReadTime > 0: - Condition:
 * lastReadTime <= 0 evaluates to FALSE - So super.timedOut() is NOT called - BUG: The idle client
 * connection should be closed!
 *
 * <p>The fix adds additional checks to avoid closing when a request is pending:
 *
 * <pre>
 * boolean requestHasBeenWritten = false;
 * if (currentServerConnection != null) {
 *     HttpRequest initialRequest = currentServerConnection.getInitialRequest();
 *     requestHasBeenWritten = initialRequest != null;
 * }
 *
 * if (currentServerConnection == null
 *     || (currentServerConnection.lastReadTime == 0
 *         && !requestHasBeenWritten
 *         && currentRequest == null)
 *     || lastReadTime <= currentServerConnection.lastReadTime) {
 *     super.timedOut();
 * }
 * </pre>
 *
 * <p>This test MUST FAIL with the current buggy code and MUST PASS when the fix is applied.
 *
 * @see <a href="https://github.com/LittleProxy/LittleProxy/issues/439">Issue #439</a>
 */
public class ClientToProxyTimeoutBugTest {

  private ChannelHandlerContext ctx;
  private Channel channel;
  private ChannelPipeline pipeline;
  private ChannelConfig channelConfig;
  private EventLoop eventLoop;
  private DefaultHttpProxyServer proxyServer;

  private ClientToProxyConnection clientToProxyConnection;

  /** Sets up the test by creating a ClientToProxyConnection with mocked dependencies. */
  @BeforeEach
  public void setUp() throws Exception {
    // Create mocks manually
    ctx = mock(ChannelHandlerContext.class);
    channel = mock(Channel.class);
    pipeline = mock(ChannelPipeline.class);
    channelConfig = mock(ChannelConfig.class);
    eventLoop = mock(EventLoop.class);
    proxyServer = mock(DefaultHttpProxyServer.class);

    // Mock channel and its components
    when(channel.pipeline()).thenReturn(pipeline);
    when(channel.config()).thenReturn(channelConfig);
    when(ctx.channel()).thenReturn(channel);
    when(ctx.pipeline()).thenReturn(pipeline);
    when(channel.eventLoop()).thenReturn(eventLoop);

    // Mock proxyServer to return valid configuration values for pipeline initialization
    when(proxyServer.getMaxInitialLineLength()).thenReturn(4096);
    when(proxyServer.getMaxHeaderSize()).thenReturn(8192);
    when(proxyServer.getMaxChunkSize()).thenReturn(8192);
    when(proxyServer.getIdleConnectionTimeout()).thenReturn(60);
    when(proxyServer.isAcceptProxyProtocol()).thenReturn(false);
    when(proxyServer.isTransparent()).thenReturn(false);
    when(proxyServer.getProxyAlias()).thenReturn("test-proxy");
    when(proxyServer.getFiltersSource())
        .thenReturn(mock(org.littleshoot.proxy.HttpFiltersSource.class));

    // Create ClientToProxyConnection - constructor is package-private
    clientToProxyConnection =
        new ClientToProxyConnection(
            proxyServer,
            null, // no SSL
            false, // don't authenticate clients
            pipeline,
            null // no traffic shaping handler
            );

    // Inject the mocked context using reflection
    Field ctxField = ProxyConnection.class.getDeclaredField("ctx");
    ctxField.setAccessible(true);
    ctxField.set(clientToProxyConnection, ctx);

    // Inject the mocked channel using reflection
    Field channelField = ProxyConnection.class.getDeclaredField("channel");
    channelField.setAccessible(true);
    channelField.set(clientToProxyConnection, channel);
  }

  /**
   * This test directly verifies the bug in ClientToProxyConnection.timedOut().
   *
   * <p>Scenario: - Client has read data (lastReadTime > 0) - Server connection exists but has never
   * read any data (lastReadTime == 0) - No request has been written to the server
   * (requestHasBeenWritten = false) - No pending request from client (currentRequest = null) - Idle
   * timeout fires on the client channel
   *
   * <p>Expected behavior (with fix): - The condition: server.lastReadTime == 0 &&
   * !requestHasBeenWritten && currentRequest == null - This evaluates to: true && true && true =
   * TRUE - So super.timedOut() SHOULD be called to close the idle client connection
   *
   * <p>Actual behavior (with bug): - The condition: lastReadTime <= 0 evaluates to FALSE - So
   * super.timedOut() is NOT called - The idle client connection is NOT closed (BUG!)
   *
   * <p>This test MUST FAIL with the current buggy code and MUST PASS when the fix is applied.
   */
  @Test
  public void testTimedOut_WhenServerNeverRead_ShouldCallSuperTimedOut() throws Exception {
    // Set up the scenario that triggers the bug:
    // 1. Client has read data (lastReadTime > 0)
    // 2. Server connection exists but has never read (lastReadTime == 0)

    // Set client.lastReadTime > 0 using reflection
    long clientLastReadTime = System.currentTimeMillis();
    Field lastReadTimeField = ProxyConnection.class.getDeclaredField("lastReadTime");
    lastReadTimeField.setAccessible(true);
    lastReadTimeField.set(clientToProxyConnection, clientLastReadTime);

    // Create a mock ProxyToServerConnection with lastReadTime = 0
    ProxyToServerConnection mockServerConnection = mock(ProxyToServerConnection.class);

    // Use reflection to set lastReadTime = 0 on the mock server connection
    Field serverLastReadTimeField = ProxyConnection.class.getDeclaredField("lastReadTime");
    serverLastReadTimeField.setAccessible(true);
    serverLastReadTimeField.set(mockServerConnection, 0L);

    // Set currentServerConnection using reflection
    Field currentServerConnectionField =
        ClientToProxyConnection.class.getDeclaredField("currentServerConnection");
    currentServerConnectionField.setAccessible(true);
    currentServerConnectionField.set(clientToProxyConnection, mockServerConnection);

    // Use Mockito spy to verify that super.timedOut() (which calls disconnect()) is called
    ClientToProxyConnection spyConnection = spy(clientToProxyConnection);

    // Stub disconnect to avoid actual channel operations - return a mock future
    ChannelFuture mockFuture = mock(ChannelFuture.class);
    doReturn(null).when(spyConnection).disconnect();

    // Call timedOut() on the spy
    Method timedOutMethod = ClientToProxyConnection.class.getDeclaredMethod("timedOut");
    timedOutMethod.setAccessible(true);
    timedOutMethod.invoke(spyConnection);

    // VERIFICATION:
    // With the FIX: disconnect() SHOULD be called because:
    //   - server.lastReadTime == 0 && !requestHasBeenWritten && currentRequest == null
    //   - evaluates to: true && true && true = TRUE
    //
    // With the BUG: disconnect() is NOT called because:
    //   - lastReadTime <= 0 evaluates to FALSE
    //   - so the whole condition is FALSE

    // This assertion will FAIL with the buggy code because disconnect() is NOT called
    // when server.lastReadTime == 0 and no request has been sent
    // With the fix, the condition evaluates to true so disconnect() IS called
    try {
      verify(spyConnection, times(1)).disconnect();
    } catch (AssertionError e) {
      throw new AssertionError(
          "BUG DETECTED: super.timedOut() was NOT called when it SHOULD have been!\n"
              + "When server.lastReadTime == 0 and client.lastReadTime > 0,\n"
              + "and no request has been sent to the server,\n"
              + "the idle timeout condition incorrectly evaluates to false.\n"
              + "The fix should check: server.lastReadTime == 0 && !requestHasBeenWritten && currentRequest == null\n"
              + "See issue #439",
          e);
    }
  }

  /**
   * Additional test to verify the behavior when currentServerConnection is null. This should always
   * call super.timedOut() - this works correctly even with the bug.
   *
   * <p>When currentServerConnection is null, the first part of the OR condition is true:
   * "currentServerConnection == null" evaluates to TRUE So disconnect() is called regardless of the
   * fix.
   */
  @Test
  public void testTimedOut_WhenNoServerConnection_ShouldCallSuperTimedOut() throws Exception {
    // Ensure currentServerConnection is null
    Field currentServerConnectionField =
        ClientToProxyConnection.class.getDeclaredField("currentServerConnection");
    currentServerConnectionField.setAccessible(true);
    currentServerConnectionField.set(clientToProxyConnection, null);

    // Set client.lastReadTime > 0
    long clientLastReadTime = System.currentTimeMillis();
    Field lastReadTimeField = ProxyConnection.class.getDeclaredField("lastReadTime");
    lastReadTimeField.setAccessible(true);
    lastReadTimeField.set(clientToProxyConnection, clientLastReadTime);

    // Use Mockito spy to verify disconnect() is called
    ClientToProxyConnection spyConnection = spy(clientToProxyConnection);
    doReturn(null).when(spyConnection).disconnect();

    // Call timedOut() using reflection
    Method timedOutMethod = ClientToProxyConnection.class.getDeclaredMethod("timedOut");
    timedOutMethod.setAccessible(true);
    timedOutMethod.invoke(spyConnection);

    // When currentServerConnection is null, the first part of the OR condition is true
    // so disconnect() should be called - this works correctly even with the bug
    verify(spyConnection, times(1)).disconnect();
  }

  /**
   * Test to verify the behavior when server.lastReadTime > client.lastReadTime.
   *
   * <p>In this case, the server has been more active (sent data more recently). The condition
   * "lastReadTime <= currentServerConnection.lastReadTime" evaluates to TRUE (e.g., 1000 <= 2000),
   * so disconnect() SHOULD be called.
   *
   * <p>This is correct behavior and works both with and without the fix.
   */
  @Test
  public void testTimedOut_WhenServerMoreActive_ShouldCallSuperTimedOut() throws Exception {
    // Set client.lastReadTime = 1000 (less recent)
    Field lastReadTimeField = ProxyConnection.class.getDeclaredField("lastReadTime");
    lastReadTimeField.setAccessible(true);
    lastReadTimeField.set(clientToProxyConnection, 1000L);

    // Create mock server connection with lastReadTime = 2000 (more recent)
    ProxyToServerConnection mockServerConnection = mock(ProxyToServerConnection.class);
    Field serverLastReadTimeField = ProxyConnection.class.getDeclaredField("lastReadTime");
    serverLastReadTimeField.setAccessible(true);
    serverLastReadTimeField.set(mockServerConnection, 2000L);

    // Set currentServerConnection
    Field currentServerConnectionField =
        ClientToProxyConnection.class.getDeclaredField("currentServerConnection");
    currentServerConnectionField.setAccessible(true);
    currentServerConnectionField.set(clientToProxyConnection, mockServerConnection);

    // Use Mockito spy to verify disconnect() is called
    ClientToProxyConnection spyConnection = spy(clientToProxyConnection);
    doReturn(null).when(spyConnection).disconnect();

    // Call timedOut() using reflection
    Method timedOutMethod = ClientToProxyConnection.class.getDeclaredMethod("timedOut");
    timedOutMethod.setAccessible(true);
    timedOutMethod.invoke(spyConnection);

    // When server.lastReadTime > client.lastReadTime, the condition
    // lastReadTime <= serverLastReadTime evaluates to true (1000 <= 2000)
    // so disconnect() SHOULD be called - this is correct behavior
    verify(spyConnection, times(1)).disconnect();
  }

  /**
   * Demonstrates the bug condition logic mathematically.
   *
   * <p>This test shows exactly what happens with the buggy condition vs the fixed condition. Note:
   * The actual fix also checks requestHasBeenWritten and currentRequest, but this test focuses on
   * the core logic issue.
   */
  @Test
  public void demonstrateBugConditionLogic() {
    // BUG DEMONSTRATION:
    // When server.lastReadTime == 0 and client.lastReadTime > 0
    // and no request has been sent to server

    long clientLastRead = 1000L; // Client has read something
    long serverLastRead = 0L; // Server has NEVER read (new connection)
    boolean requestHasBeenWritten = false; // No request sent to server
    Object currentRequest = null; // No pending request

    // Current buggy condition (simplified):
    // lastReadTime <= currentServerConnection.lastReadTime
    boolean buggyCondition = (clientLastRead <= serverLastRead);

    // The buggy condition evaluates to: 1000 <= 0 = FALSE
    assertThat(buggyCondition).isFalse();

    // This means super.timedOut() is NOT called - BUG!
    // The client connection should be closed but it's not!

    // Fixed condition (actual fix):
    // (server.lastReadTime == 0 && !requestHasBeenWritten && currentRequest == null)
    //     || lastReadTime <= server.lastReadTime
    boolean fixedCondition =
        (serverLastRead == 0 && !requestHasBeenWritten && currentRequest == null)
            || (clientLastRead <= serverLastRead);

    // The fixed condition evaluates to: (true && true && true) || false = TRUE
    assertThat(fixedCondition).isTrue();

    // This means super.timedOut() IS called - CORRECT!

    // This demonstrates the bug clearly:
    // - With buggy code: timeout handling is SKIPPED (wrong!)
    // - With fixed code: timeout handling is EXECUTED (correct!)
  }
}
