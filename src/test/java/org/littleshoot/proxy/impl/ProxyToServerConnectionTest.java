package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;

class ProxyToServerConnectionTest {

  private DefaultHttpProxyServer mockProxyServer;
  private ClientToProxyConnection mockClientConnection;
  private HttpFilters mockFilters;
  private GlobalTrafficShapingHandler mockTrafficHandler;
  private HostResolver mockHostResolver;
  private FlowContext mockClientFlowContext;
  private FullFlowContext mockFlowContext;

  @BeforeEach
  void setup() throws Exception {
    mockProxyServer = mock();
    mockClientConnection = mock();
    mockFilters = mock();
    mockTrafficHandler = mock();
    mockHostResolver = mock();

    when(mockProxyServer.getServerResolver()).thenReturn(mockHostResolver);
    when(mockHostResolver.resolve(any(), anyInt()))
        .thenReturn(new InetSocketAddress("127.0.0.1", 8080));

    mockClientFlowContext = mock();
    when(mockClientConnection.flowContext()).thenReturn(mockClientFlowContext);
    mockFlowContext = mock();
    when(mockClientConnection.flowContextForServerConnection(any(ProxyToServerConnection.class)))
        .thenReturn(mockFlowContext);
  }

  private ProxyToServerConnection createConnection(List<ActivityTracker> trackers)
      throws UnknownHostException {
    when(mockProxyServer.getActivityTrackers()).thenReturn(trackers);
    return ProxyToServerConnection.create(
        mockProxyServer,
        mockClientConnection,
        "localhost:8080",
        mockFilters,
        null,
        mockTrafficHandler);
  }

  @Test
  @DisplayName("disconnected should clear flow context even when ActivityTracker throws exception")
  void disconnectedShouldClearFlowContextEvenWhenActivityTrackerThrowsException() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .serverDisconnected(any(), any());

    List<ActivityTracker> trackers = Collections.singletonList(throwingTracker);
    ProxyToServerConnection connection = createConnection(trackers);
    assertThat(connection).isNotNull();

    connection.disconnected();

    verify(throwingTracker)
        .serverDisconnected(any(FullFlowContext.class), any(InetSocketAddress.class));
    verify(mockClientConnection).clearFlowContextForServerConnection(connection);
  }

  @Test
  @DisplayName("disconnected should clear flow context when no exception occurs")
  void disconnectedShouldClearFlowContextWhenNoException() throws Exception {
    ActivityTracker normalTracker = mock();
    List<ActivityTracker> trackers = Collections.singletonList(normalTracker);
    ProxyToServerConnection connection = createConnection(trackers);
    assertThat(connection).isNotNull();

    connection.disconnected();

    verify(normalTracker)
        .serverDisconnected(any(FullFlowContext.class), any(InetSocketAddress.class));
    verify(mockClientConnection).clearFlowContextForServerConnection(connection);
  }

  private void invokeRecordMethod(ProxyToServerConnection connection, String methodName)
      throws Exception {
    Method method = ProxyToServerConnection.class.getDeclaredMethod(methodName);
    method.setAccessible(true);
    method.invoke(connection);
  }

  @Test
  @DisplayName("serverConnected should notify all trackers even if one throws")
  void serverConnectedShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .serverConnected(any(), any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    invokeRecordMethod(connection, "recordServerConnected");

    verify(throwingTracker).serverConnected(any(), any());
    verify(succeedingTracker).serverConnected(any(), any());
  }

  @Test
  @DisplayName(
      "serverDisconnected should notify all trackers even if one throws and still clear context")
  void serverDisconnectedShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .serverDisconnected(any(), any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    invokeRecordMethod(connection, "recordServerDisconnected");

    verify(throwingTracker).serverDisconnected(any(), any());
    verify(succeedingTracker).serverDisconnected(any(), any());
    verify(mockClientConnection).clearFlowContextForServerConnection(connection);
  }

  @Test
  @DisplayName("connectionSaturated should notify all trackers even if one throws")
  void connectionSaturatedShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .connectionSaturated(any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    invokeRecordMethod(connection, "recordConnectionSaturated");

    verify(throwingTracker).connectionSaturated(any());
    verify(succeedingTracker).connectionSaturated(any());
  }

  @Test
  @DisplayName("connectionWritable should notify all trackers even if one throws")
  void connectionWritableShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception")).when(throwingTracker).connectionWritable(any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    invokeRecordMethod(connection, "recordConnectionWritable");

    verify(throwingTracker).connectionWritable(any());
    verify(succeedingTracker).connectionWritable(any());
  }

  @Test
  @DisplayName("connectionTimedOut should notify all trackers even if one throws")
  void connectionTimedOutShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception")).when(throwingTracker).connectionTimedOut(any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    invokeRecordMethod(connection, "recordConnectionTimedOut");

    verify(throwingTracker).connectionTimedOut(any());
    verify(succeedingTracker).connectionTimedOut(any());
  }

  @Test
  @DisplayName("connectionExceptionCaught should notify all trackers even if one throws")
  void connectionExceptionCaughtShouldNotifyAllTrackersEvenIfOneThrows() throws Exception {
    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .connectionExceptionCaught(any(), any());
    ActivityTracker succeedingTracker = mock();

    ProxyToServerConnection connection =
        createConnection(Arrays.asList(throwingTracker, succeedingTracker));
    assertThat(connection).isNotNull();

    Method method =
        ProxyToServerConnection.class.getDeclaredMethod(
            "recordConnectionExceptionCaught", Throwable.class);
    method.setAccessible(true);
    method.invoke(connection, new RuntimeException("Test cause"));

    verify(throwingTracker).connectionExceptionCaught(any(), any());
    verify(succeedingTracker).connectionExceptionCaught(any(), any());
  }
}
