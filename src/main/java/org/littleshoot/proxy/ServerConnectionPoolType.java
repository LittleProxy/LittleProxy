package org.littleshoot.proxy;

/** Defines which implementation backs the shared server connection pool. */
public enum ServerConnectionPoolType {
  /** Simple ConcurrentHashMap-based pool. */
  CONCURRENT_MAP,
  /** Apache Commons Pool 2 backed pool. */
  COMMONS_POOL2,
  /** Stormpot-backed pool for high performance. */
  STORMPOT
}
