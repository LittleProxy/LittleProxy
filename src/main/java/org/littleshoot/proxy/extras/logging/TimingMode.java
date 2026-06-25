package org.littleshoot.proxy.extras.logging;

/** Controls which timing metrics are included in logs. */
public enum TimingMode {
  /** No timing data is logged. */
  OFF,
  /** Only basic request processing time is logged. */
  MINIMAL,
  /** All timing metrics are logged. */
  ALL
}
