package com.sajtech.identity.infrastructure.quota;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.application.registration.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ClockSafetyGuardTest {
  @Test
  void commonModeWallStepTripsAndRequiresSixtyStableSeconds() {
    MutableClock clock = new MutableClock(1_000_000);
    AtomicLong mono = new AtomicLong();
    ClockSafetyGuard guard = new ClockSafetyGuard(clock, mono::get);
    assertThat(guard.requireHealthy(true)).isEqualTo(1_000_000);
    clock.advance(1000);
    mono.addAndGet(1_000_000_000L);
    assertThat(guard.requireHealthy(true)).isEqualTo(1_001_000);
    clock.advance(5000);
    mono.addAndGet(1_000_000_000L);
    assertThatThrownBy(() -> guard.requireHealthy(true))
        .isInstanceOf(RegistrationException.class)
        .extracting(e -> ((RegistrationException) e).error())
        .isEqualTo(RegistrationError.QUOTA_TIME_SOURCE_UNHEALTHY);
    for (int i = 0; i < 60; i++) {
      clock.advance(1000);
      mono.addAndGet(1_000_000_000L);
      assertThatThrownBy(() -> guard.requireHealthy(true))
          .isInstanceOf(RegistrationException.class);
    }
    clock.advance(1000);
    mono.addAndGet(1_000_000_000L);
    assertThat(guard.requireHealthy(true)).isEqualTo(clock.millis());
  }

  private static final class MutableClock extends Clock {
    private long ms;

    MutableClock(long ms) {
      this.ms = ms;
    }

    void advance(long value) {
      ms += value;
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
      return Instant.ofEpochMilli(ms);
    }

    @Override
    public long millis() {
      return ms;
    }
  }
}
