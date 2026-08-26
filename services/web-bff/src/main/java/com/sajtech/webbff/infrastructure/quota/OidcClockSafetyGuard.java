package com.sajtech.webbff.infrastructure.quota;

import com.sajtech.webbff.application.*;
import java.time.*;
import java.util.function.LongSupplier;

public final class OidcClockSafetyGuard {
  private static final long STEP_THRESHOLD_MS = 2000;
  private static final long REARM_MS = Duration.ofSeconds(60).toMillis();
  private final Clock clock;
  private final LongSupplier monotonicNanos;
  private long lastWallMs;
  private long lastMonotonicNanos;
  private long stableSinceMonotonicNanos = -1;
  private boolean initialized;
  private boolean tripped;

  public OidcClockSafetyGuard(Clock clock) {
    this(clock, System::nanoTime);
  }

  OidcClockSafetyGuard(Clock clock, LongSupplier monotonicNanos) {
    this.clock = clock;
    this.monotonicNanos = monotonicNanos;
  }

  public synchronized long requireHealthy(boolean hostSynchronized) {
    long wall = clock.millis();
    long monotonic = monotonicNanos.getAsLong();
    if (!initialized) {
      initialized = true;
      lastWallMs = wall;
      lastMonotonicNanos = monotonic;
      if (!hostSynchronized) fail();
      return wall;
    }
    long divergence =
        Math.abs((wall - lastWallMs) - ((monotonic - lastMonotonicNanos) / 1_000_000L));
    lastWallMs = wall;
    lastMonotonicNanos = monotonic;
    if (divergence > STEP_THRESHOLD_MS) {
      tripped = true;
      stableSinceMonotonicNanos = -1;
      fail();
    }
    if (!hostSynchronized) {
      stableSinceMonotonicNanos = -1;
      fail();
    }
    if (tripped) {
      if (stableSinceMonotonicNanos < 0) stableSinceMonotonicNanos = monotonic;
      if ((monotonic - stableSinceMonotonicNanos) / 1_000_000L < REARM_MS) fail();
      tripped = false;
      stableSinceMonotonicNanos = -1;
    }
    return wall;
  }

  public synchronized boolean isHealthy(boolean hostSynchronized) {
    try {
      requireHealthy(hostSynchronized);
      return true;
    } catch (BffException exception) {
      return false;
    }
  }

  private static void fail() {
    throw new BffException(
        BffError.QUOTA_TIME_SOURCE_UNHEALTHY, "OIDC quota time source is unavailable");
  }
}
