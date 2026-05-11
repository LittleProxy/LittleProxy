package org.littleshoot.proxy.impl;

/** Pool metrics statistics. */
public class PoolMetrics {
  private final int totalConnections;
  private final int activeConnections;
  private final int idleConnections;
  private final long borrowCount;
  private final long returnCount;
  private final long evictionCount;
  private final long validationFailureCount;

  public PoolMetrics(
      int totalConnections,
      int activeConnections,
      int idleConnections,
      long borrowCount,
      long returnCount,
      long evictionCount,
      long validationFailureCount) {
    this.totalConnections = totalConnections;
    this.activeConnections = activeConnections;
    this.idleConnections = idleConnections;
    this.borrowCount = borrowCount;
    this.returnCount = returnCount;
    this.evictionCount = evictionCount;
    this.validationFailureCount = validationFailureCount;
  }

  public int getTotalConnections() {
    return totalConnections;
  }

  public int getActiveConnections() {
    return activeConnections;
  }

  public int getIdleConnections() {
    return idleConnections;
  }

  public long getBorrowCount() {
    return borrowCount;
  }

  public long getReturnCount() {
    return returnCount;
  }

  public long getEvictionCount() {
    return evictionCount;
  }

  public long getValidationFailureCount() {
    return validationFailureCount;
  }

  @Override
  public String toString() {
    return "PoolMetrics{"
        + "total="
        + totalConnections
        + ", active="
        + activeConnections
        + ", idle="
        + idleConnections
        + ", borrowCount="
        + borrowCount
        + ", returnCount="
        + returnCount
        + ", evictionCount="
        + evictionCount
        + ", validationFailureCount="
        + validationFailureCount
        + '}';
  }
}
