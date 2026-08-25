package com.sajtech.webbff.infrastructure.quota;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.webbff.application.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OidcClockSafetyGuardTest {
  @Test
  void commonModeWallClockStepTripsAndRequiresSixtyStableSecondsToRearm() {
    MutableClock clock = new MutableClock(1_000_000L);
    AtomicLong monotonic = new AtomicLong(1_000_000_000L);
    OidcClockSafetyGuard guard = new OidcClockSafetyGuard(clock, monotonic::get);
    assertThat(guard.requireHealthy(true)).isEqualTo(1_000_000L);
    clock.millis += 5_000;
    monotonic.addAndGet(1_000_000_000L);

    assertThatThrownBy(() -> guard.requireHealthy(true))
        .isInstanceOfSatisfying(
            BffException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(BffError.QUOTA_TIME_SOURCE_UNHEALTHY));

    for (int index = 0; index < 60; index++) {
      clock.millis += 1_000;
      monotonic.addAndGet(1_000_000_000L);
      assertThatThrownBy(() -> guard.requireHealthy(true)).isInstanceOf(BffException.class);
    }
    clock.millis += 1_000;
    monotonic.addAndGet(1_000_000_000L);
    assertThat(guard.requireHealthy(true)).isEqualTo(clock.millis);
  }

  private static final class MutableClock extends Clock {
    private long millis;

    private MutableClock(long millis) {
      this.millis = millis;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
      return millis;
    }
  }
}
