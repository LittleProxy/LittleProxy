package org.littleshoot.proxy.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.FlowContext;

class ClientToProxyConnectionTest {

  @Test
  @DisplayName("recordClientDisconnected should call all trackers even if one throws")
  void recordClientDisconnectedShouldCallAllTrackersEvenIfOneThrows() throws Exception {
    DefaultHttpProxyServer mockProxyServer = mock();
    ClientToProxyConnection mockConnection = mock();

    ActivityTracker throwingTracker = mock();
    doThrow(new RuntimeException("Test exception"))
        .when(throwingTracker)
        .clientDisconnected(any(), any());

    ActivityTracker normalTracker = mock();

    List<ActivityTracker> trackers = new ArrayList<>();
    trackers.add(throwingTracker);
    trackers.add(normalTracker);
    when(mockProxyServer.getActivityTrackers()).thenReturn(trackers);

    FlowContext mockFlowContext = mock();
    when(mockConnection.flowContext()).thenReturn(mockFlowContext);

    Field proxyServerField = ProxyConnection.class.getDeclaredField("proxyServer");
    proxyServerField.setAccessible(true);
    proxyServerField.set(mockConnection, mockProxyServer);

    ProxyConnectionLogger mockLogger = mock(ProxyConnectionLogger.class);
    Field logField = ProxyConnection.class.getDeclaredField("logger");
    logField.setAccessible(true);
    logField.set(mockConnection, mockLogger);

    Method recordMethod =
        ClientToProxyConnection.class.getDeclaredMethod("recordClientDisconnected");
    recordMethod.setAccessible(true);
    recordMethod.invoke(mockConnection);

    verify(throwingTracker).clientDisconnected(eq(mockFlowContext), any());
    verify(normalTracker).clientDisconnected(eq(mockFlowContext), any());
  }

  @Test
  @DisplayName("recordClientDisconnected should call trackers when no exception occurs")
  void recordClientDisconnectedShouldCallTrackersWhenNoException() throws Exception {
    DefaultHttpProxyServer mockProxyServer = mock();
    ClientToProxyConnection mockConnection = mock();

    ActivityTracker normalTracker = mock();

    List<ActivityTracker> trackers = new ArrayList<>();
    trackers.add(normalTracker);
    when(mockProxyServer.getActivityTrackers()).thenReturn(trackers);

    FlowContext mockFlowContext = mock();
    when(mockConnection.flowContext()).thenReturn(mockFlowContext);

    Field proxyServerField = ProxyConnection.class.getDeclaredField("proxyServer");
    proxyServerField.setAccessible(true);
    proxyServerField.set(mockConnection, mockProxyServer);

    ProxyConnectionLogger mockLogger = mock(ProxyConnectionLogger.class);
    Field logField = ProxyConnection.class.getDeclaredField("logger");
    logField.setAccessible(true);
    logField.set(mockConnection, mockLogger);

    Method recordMethod =
        ClientToProxyConnection.class.getDeclaredMethod("recordClientDisconnected");
    recordMethod.setAccessible(true);
    recordMethod.invoke(mockConnection);

    verify(normalTracker).clientDisconnected(eq(mockFlowContext), any());
  }
}
