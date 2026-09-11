package org.littleshoot.proxy;

/** Defines which implementation backs the shared server connection pool. */
public enum ServerConnectionPoolType {
  /** Simple ConcurrentHashMap-based pool. */
  CONCURRENT_MAP
}
