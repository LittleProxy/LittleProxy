package org.littleshoot.proxy.impl;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFiltersSource;
import org.littleshoot.proxy.MitmManager;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.SslEngineSource;
import org.littleshoot.proxy.TransportProtocol;

public class DefaultHttpProxyServerConfig {
  private TransportProtocol transportProtocol;
  private InetSocketAddress requestedAddress;
  @Nullable private SslEngineSource sslEngineSource;
  private boolean authenticateSslClients;
  @Nullable private ProxyAuthenticator proxyAuthenticator;
  @Nullable private ChainedProxyManager chainProxyManager;
  @Nullable private MitmManager mitmManager;
  private HttpFiltersSource filtersSource;
  private boolean transparent;
  private Duration idleConnectionTimeout;
  private final Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<>();
  private int connectTimeout;
  private HostResolver serverResolver;
  private long readThrottleBytesPerSecond;
  private long writeThrottleBytesPerSecond;
  @Nullable private InetSocketAddress localAddress;
  @Nullable private String proxyAlias;
  private int maxInitialLineLength;
  private int maxHeaderSize;
  private int maxChunkSize;
  private boolean allowRequestsToOriginServer;
  private boolean acceptProxyProtocol;
  private boolean sendProxyProtocol;
  private ServerConnectionPoolConfig serverConnectionPoolConfig;

  public TransportProtocol getTransportProtocol() {
    return transportProtocol;
  }

  public DefaultHttpProxyServerConfig setTransportProtocol(TransportProtocol transportProtocol) {
    this.transportProtocol = transportProtocol;
    return this;
  }

  public InetSocketAddress getRequestedAddress() {
    return requestedAddress;
  }

  public DefaultHttpProxyServerConfig setRequestedAddress(InetSocketAddress requestedAddress) {
    this.requestedAddress = requestedAddress;
    return this;
  }

  @Nullable
  public SslEngineSource getSslEngineSource() {
    return sslEngineSource;
  }

  public DefaultHttpProxyServerConfig setSslEngineSource(
      @Nullable SslEngineSource sslEngineSource) {
    this.sslEngineSource = sslEngineSource;
    return this;
  }

  public boolean isAuthenticateSslClients() {
    return authenticateSslClients;
  }

  public DefaultHttpProxyServerConfig setAuthenticateSslClients(boolean authenticateSslClients) {
    this.authenticateSslClients = authenticateSslClients;
    return this;
  }

  @Nullable
  public ProxyAuthenticator getProxyAuthenticator() {
    return proxyAuthenticator;
  }

  public DefaultHttpProxyServerConfig setProxyAuthenticator(
      @Nullable ProxyAuthenticator proxyAuthenticator) {
    this.proxyAuthenticator = proxyAuthenticator;
    return this;
  }

  @Nullable
  public ChainedProxyManager getChainProxyManager() {
    return chainProxyManager;
  }

  public DefaultHttpProxyServerConfig setChainProxyManager(
      @Nullable ChainedProxyManager chainProxyManager) {
    this.chainProxyManager = chainProxyManager;
    return this;
  }

  @Nullable
  public MitmManager getMitmManager() {
    return mitmManager;
  }

  public DefaultHttpProxyServerConfig setMitmManager(@Nullable MitmManager mitmManager) {
    this.mitmManager = mitmManager;
    return this;
  }

  public HttpFiltersSource getFiltersSource() {
    return filtersSource;
  }

  public DefaultHttpProxyServerConfig setFiltersSource(HttpFiltersSource filtersSource) {
    this.filtersSource = filtersSource;
    return this;
  }

  public boolean isTransparent() {
    return transparent;
  }

  public DefaultHttpProxyServerConfig setTransparent(boolean transparent) {
    this.transparent = transparent;
    return this;
  }

  public Duration getIdleConnectionTimeout() {
    return idleConnectionTimeout;
  }

  public DefaultHttpProxyServerConfig setIdleConnectionTimeout(Duration idleConnectionTimeout) {
    this.idleConnectionTimeout = idleConnectionTimeout;
    return this;
  }

  public Collection<ActivityTracker> getActivityTrackers() {
    return activityTrackers;
  }

  public DefaultHttpProxyServerConfig setActivityTrackers(
      Collection<ActivityTracker> activityTrackers) {
    this.activityTrackers.clear();
    this.activityTrackers.addAll(activityTrackers);
    return this;
  }

  public int getConnectTimeout() {
    return connectTimeout;
  }

  public DefaultHttpProxyServerConfig setConnectTimeout(int connectTimeout) {
    this.connectTimeout = connectTimeout;
    return this;
  }

  public HostResolver getServerResolver() {
    return serverResolver;
  }

  public DefaultHttpProxyServerConfig setServerResolver(HostResolver serverResolver) {
    this.serverResolver = serverResolver;
    return this;
  }

  public long getReadThrottleBytesPerSecond() {
    return readThrottleBytesPerSecond;
  }

  public DefaultHttpProxyServerConfig setReadThrottleBytesPerSecond(
      long readThrottleBytesPerSecond) {
    this.readThrottleBytesPerSecond = readThrottleBytesPerSecond;
    return this;
  }

  public long getWriteThrottleBytesPerSecond() {
    return writeThrottleBytesPerSecond;
  }

  public DefaultHttpProxyServerConfig setWriteThrottleBytesPerSecond(
      long writeThrottleBytesPerSecond) {
    this.writeThrottleBytesPerSecond = writeThrottleBytesPerSecond;
    return this;
  }

  @Nullable
  public InetSocketAddress getLocalAddress() {
    return localAddress;
  }

  public DefaultHttpProxyServerConfig setLocalAddress(@Nullable InetSocketAddress localAddress) {
    this.localAddress = localAddress;
    return this;
  }

  @Nullable
  public String getProxyAlias() {
    return proxyAlias;
  }

  public DefaultHttpProxyServerConfig setProxyAlias(@Nullable String proxyAlias) {
    this.proxyAlias = proxyAlias;
    return this;
  }

  public int getMaxInitialLineLength() {
    return maxInitialLineLength;
  }

  public DefaultHttpProxyServerConfig setMaxInitialLineLength(int maxInitialLineLength) {
    this.maxInitialLineLength = maxInitialLineLength;
    return this;
  }

  public int getMaxHeaderSize() {
    return maxHeaderSize;
  }

  public DefaultHttpProxyServerConfig setMaxHeaderSize(int maxHeaderSize) {
    this.maxHeaderSize = maxHeaderSize;
    return this;
  }

  public int getMaxChunkSize() {
    return maxChunkSize;
  }

  public DefaultHttpProxyServerConfig setMaxChunkSize(int maxChunkSize) {
    this.maxChunkSize = maxChunkSize;
    return this;
  }

  public boolean isAllowRequestsToOriginServer() {
    return allowRequestsToOriginServer;
  }

  public DefaultHttpProxyServerConfig setAllowRequestsToOriginServer(
      boolean allowRequestsToOriginServer) {
    this.allowRequestsToOriginServer = allowRequestsToOriginServer;
    return this;
  }

  public boolean isAcceptProxyProtocol() {
    return acceptProxyProtocol;
  }

  public DefaultHttpProxyServerConfig setAcceptProxyProtocol(boolean acceptProxyProtocol) {
    this.acceptProxyProtocol = acceptProxyProtocol;
    return this;
  }

  public boolean isSendProxyProtocol() {
    return sendProxyProtocol;
  }

  public DefaultHttpProxyServerConfig setSendProxyProtocol(boolean sendProxyProtocol) {
    this.sendProxyProtocol = sendProxyProtocol;
    return this;
  }

  public ServerConnectionPoolConfig getServerConnectionPoolConfig() {
    return serverConnectionPoolConfig;
  }

  public DefaultHttpProxyServerConfig setServerConnectionPoolConfig(
      ServerConnectionPoolConfig serverConnectionPoolConfig) {
    this.serverConnectionPoolConfig = serverConnectionPoolConfig;
    return this;
  }
}
