package com.sajtech.compromisedpassword.infrastructure.lookup.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.compromisedpassword.application.lookup.LookupOverloadedException;
import com.sajtech.compromisedpassword.application.lookup.LookupUnavailableException;
import com.sajtech.compromisedpassword.application.lookup.port.in.LookupCompromisedPasswords;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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

  @Test
  void observationStartFailureDoesNotFailLookupOrLeakPermit() {
    ObservationRegistry observations = ObservationRegistry.create();
    observations
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public void onStart(Observation.Context context) {
                throw new IllegalStateException("sensitive telemetry failure");
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
    BoundedLookupCompromisedPasswords bounded =
        new BoundedLookupCompromisedPasswords(
            prefix -> List.of(), 1, observations, new SimpleMeterRegistry());

    assertThat(bounded.lookup(Sha1Prefix.parse("ABCDE"))).isEmpty();
    assertThat(bounded.lookup(Sha1Prefix.parse("12345"))).isEmpty();
  }

  @Test
  void businessFailureIsNotAttachedToObservationAsRawException() {
    AtomicInteger observationErrors = new AtomicInteger();
    ObservationRegistry observations = ObservationRegistry.create();
    observations
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public void onError(Observation.Context context) {
                observationErrors.incrementAndGet();
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
    BoundedLookupCompromisedPasswords bounded =
        new BoundedLookupCompromisedPasswords(
            prefix -> {
              throw new LookupUnavailableException("/sensitive/dataset/path");
            },
            1,
            observations,
            new SimpleMeterRegistry());

    assertThatThrownBy(() -> bounded.lookup(Sha1Prefix.parse("ABCDE")))
        .isInstanceOf(LookupUnavailableException.class);
    assertThat(observationErrors).hasValue(0);
  }
}
