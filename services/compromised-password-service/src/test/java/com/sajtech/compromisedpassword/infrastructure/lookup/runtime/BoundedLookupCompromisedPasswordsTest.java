package com.sajtech.compromisedpassword.infrastructure.lookup.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.compromisedpassword.application.lookup.LookupOverloadedException;
import com.sajtech.compromisedpassword.application.lookup.port.in.LookupCompromisedPasswords;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class BoundedLookupCompromisedPasswordsTest {
  @Test
  void rejectsExcessConcurrencyWithoutQueueingOrSensitiveMetricLabels() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    LookupCompromisedPasswords blockingDelegate =
        prefix -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", exception);
          }
          return List.of();
        };
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    BoundedLookupCompromisedPasswords bounded =
        new BoundedLookupCompromisedPasswords(
            blockingDelegate, 1, ObservationRegistry.create(), meters);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> first = executor.submit(() -> bounded.lookup(Sha1Prefix.parse("ABCDE")));
      entered.await();

      assertThatThrownBy(() -> bounded.lookup(Sha1Prefix.parse("12345")))
          .isInstanceOf(LookupOverloadedException.class);

      release.countDown();
      first.get();
    }

    assertThat(meters.get("compromised_password.lookup.rejected").counter().count()).isEqualTo(1.0);
    assertThat(meters.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .allSatisfy(
                        tag -> assertThat(tag.getKey()).doesNotContainIgnoringCase("prefix")));
  }
}
