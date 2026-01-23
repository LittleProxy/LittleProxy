package org.littleshoot.proxy.extras;

/** Enumeration of supported log formats for the {@link ActivityLogger}. */
public enum LogFormat {
  /** Common Log Format (CLF). host ident authuser [date] "request" status bytes */
  CLF,

  /**
   * Extended Log Format (ELF). Similar to w3c but often customizable. We'll use a standard extended
   * set.
   */
  ELF,

  /** JSON Format. Structured log in JSON. */
  JSON,

  /**
   * Squid Native access log format. time elapsed remotehost code/status bytes method URL rfc931
   * peerstatus/peerhost type
   */
  SQUID,

  /** W3C Extended Log File Format. */
  W3C,

  /** Labeled Tab-Separated Values (LTSV). label:value\tlabel2:value2 */
  LTSV,

  /** Comma-Separated Values (CSV). "timestamp","host","method","uri",... */
  CSV,

  /** HAProxy HTTP Log Format. Includes detailed timing information. */
  HAPROXY
}
