package com.sajtech.identity.infrastructure.quota;

import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import java.time.Clock;
import java.time.Duration;
import java.util.function.LongSupplier;

public final class ClockSafetyGuard {
  private static final long STEP_THRESHOLD_MS = 2000;
  private static final long REARM_MS = Duration.ofSeconds(60).toMillis();
  private final Clock clock;
  private final LongSupplier monotonicNanos;
  private long lastWallMs;
  private long lastMonotonicNanos;
  private long stableSinceMonotonicNanos = -1;
  private boolean initialized;
  private boolean tripped;

  public ClockSafetyGuard(Clock clock) {
    this(clock, System::nanoTime);
  }

  ClockSafetyGuard(Clock clock, LongSupplier monotonicNanos) {
    this.clock = clock;
    this.monotonicNanos = monotonicNanos;
  }

  public synchronized long requireHealthy(boolean hostSynchronized) {
    long wall = clock.millis();
    long mono = monotonicNanos.getAsLong();
    if (!initialized) {
      initialized = true;
      lastWallMs = wall;
      lastMonotonicNanos = mono;
      if (!hostSynchronized) fail();
      return wall;
    }
    long wallElapsed = wall - lastWallMs;
    long monoElapsed = (mono - lastMonotonicNanos) / 1_000_000L;
    long divergence = Math.abs(wallElapsed - monoElapsed);
    lastWallMs = wall;
    lastMonotonicNanos = mono;
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
      if (stableSinceMonotonicNanos < 0) stableSinceMonotonicNanos = mono;
      if ((mono - stableSinceMonotonicNanos) / 1_000_000L < REARM_MS) fail();
      tripped = false;
      stableSinceMonotonicNanos = -1;
    }
    return wall;
  }

  public synchronized boolean isHealthy(boolean hostSynchronized) {
    try {
      requireHealthy(hostSynchronized);
      return true;
    } catch (RegistrationException exception) {
      return false;
    }
  }

  private static void fail() {
    throw new RegistrationException(
        RegistrationError.QUOTA_TIME_SOURCE_UNHEALTHY, "Quota time source is unavailable");
  }
}
