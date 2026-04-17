package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class TimedRequest {
  private final HttpRequest request;
  private final long startTime;
  private final String clientConnectionId;
  private final String requestId;
  private volatile String serverConnectionId;
  private final Map<String, Long> data = new ConcurrentHashMap<>();

  public TimedRequest(
      HttpRequest request, long startTime, String clientConnectionId, String requestId) {
    this.request = request;
    this.startTime = startTime;
    this.clientConnectionId = clientConnectionId;
    this.requestId = requestId;
  }

  public TimedRequest(
      HttpRequest request,
      long startTime,
      String clientConnectionId,
      String requestId,
      Map<String, Long> timingData) {
    this(request, startTime, clientConnectionId, requestId);
    if (timingData != null) {
      data.putAll(timingData);
    }
  }

  public HttpRequest getRequest() {
    return request;
  }

  public long getStartTime() {
    return startTime;
  }

  public String getClientConnectionId() {
    return clientConnectionId;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getServerConnectionId() {
    return serverConnectionId;
  }

  public void setServerConnectionId(String serverConnectionId) {
    this.serverConnectionId = serverConnectionId;
  }

  public void setTimingData(String key, Long value) {
    Objects.requireNonNull(key, "timing key must not be null");
    Objects.requireNonNull(value, "timing value must not be null");
    data.put(key, value);
  }

  public Long getTimingData(String key) {
    return data.get(key);
  }

  public Map<String, Long> getTimings() {
    return Map.copyOf(data);
  }
}
