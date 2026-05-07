package com.vex.bench;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tiny shim that routes bench-tool stdout through SLF4J. The bench tools used to call {@code
 * System.out.println / printf} directly, but the project rule (per the spec) is no {@code
 * System.out} in committed code. The logback config in {@code bench/src/main/resources/logback.xml}
 * uses a {@code %msg%n} pattern so the rendered output reads exactly like raw stdout.
 */
final class BenchOut {

  private static final Logger LOG = LoggerFactory.getLogger("vex.bench");

  private BenchOut() {}

  /** Blank-line shim for the println()-with-no-args case. */
  static void info() {
    LOG.info("");
  }

  /** Logs a plain line at INFO. */
  static void info(String msg) {
    LOG.info(msg);
  }

  /** Logs a printf-formatted line at INFO. */
  static void infof(String fmt, Object... args) {
    LOG.info(String.format(fmt, args));
  }

  /** Logs a plain line at ERROR (sent to stderr by the logback config). */
  static void err(String msg) {
    LOG.error(msg);
  }

  /** Logs a printf-formatted line at ERROR. */
  static void errf(String fmt, Object... args) {
    LOG.error(String.format(fmt, args));
  }
}
