package com.sajtech.identity.infrastructure.worker;

import static com.sajtech.identity.application.transaction.model.TransactionProfile.WORK_CLAIM;

import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.infrastructure.persistence.JooqIdentityErasureParticipant;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.scheduling.annotation.Scheduled;

public final class IdentityErasureWorker {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final int MAX_ATTEMPTS = 48;
  private final JooqIdentityErasureParticipant repository;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final io.micrometer.core.instrument.Counter completed;
  private final io.micrometer.core.instrument.Counter retries;
  private final io.micrometer.core.instrument.Counter exhausted;

  public IdentityErasureWorker(
      JooqIdentityErasureParticipant repository,
      TransactionRunner transactions,
      Clock clock,
      MeterRegistry meters) {
    this.repository = repository;
    this.transactions = transactions;
    this.clock = clock;
    completed = meters.counter("identity.erasure.participant.completed");
    retries = meters.counter("identity.erasure.participant.retries");
    exhausted = meters.counter("identity.erasure.participant.exhausted");
  }

  @Scheduled(fixedDelayString = "${identity.erasure-worker-delay:PT1S}")
  public void run() {
    Instant now = clock.instant();
    var claimed = transactions.required(WORK_CLAIM, () -> repository.claim(now, LEASE));
    if (claimed.isEmpty()) return;
    var item = claimed.get();
    try {
      transactions.required(
          () -> {
            repository.erase(item, clock.instant());
            return null;
          });
      completed.increment();
    } catch (RuntimeException failure) {
      fail(item, failure);
    }
  }

  private void fail(JooqIdentityErasureParticipant.InboxItem item, RuntimeException failure) {
    int attempt = item.attemptCount() + 1;
    String error = safeError(failure);
    if (attempt >= MAX_ATTEMPTS) {
      transactions.required(
          () -> {
            repository.exhaust(item.eventId(), attempt, error);
            return null;
          });
      exhausted.increment();
    } else {
      Instant next = clock.instant().plus(backoff(attempt));
      transactions.required(
          () -> {
            repository.reschedule(item.eventId(), attempt, next, error);
            return null;
          });
      retries.increment();
    }
  }

  private static Duration backoff(int attempt) {
    return Duration.ofSeconds(Math.min(1800, 1L << Math.min(attempt - 1, 10)));
  }

  private static String safeError(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null) root = root.getCause();
    String value =
        root.getClass().getSimpleName().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    if (value.isEmpty() || !Character.isLetter(value.charAt(0))) value = "PROCESSING_FAILURE";
    return value.substring(0, Math.min(64, value.length()));
  }
}
