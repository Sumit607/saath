package com.saath.core;

/** The host's monotonic clock in milliseconds. Same base as Android's AudioTimestamp (CLOCK_MONOTONIC). */
public final class HostClock {
  private HostClock() {}

  public static double nowMs() { return System.nanoTime() / 1e6; }

  public static void sleepMs(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
