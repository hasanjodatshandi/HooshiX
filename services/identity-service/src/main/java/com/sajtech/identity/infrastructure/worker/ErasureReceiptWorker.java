package com.sajtech.identity.infrastructure.worker;

import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.infrastructure.persistence.JooqErasureReceiptCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;

public final class ErasureReceiptWorker {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final int MAX_ATTEMPTS = 48;
  private final JooqErasureReceiptCoordinator coordinator;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final io.micrometer.core.instrument.Counter completed;
  private final io.micrometer.core.instrument.Counter retries;
  private final io.micrometer.core.instrument.Counter exhausted;

  public ErasureReceiptWorker(
      JooqErasureReceiptCoordinator coordinator,
      TransactionRunner transactions,
      Clock clock,
      MeterRegistry meters) {
    this.coordinator = coordinator;
    this.transactions = transactions;
    this.clock = clock;
    completed = meters.counter("identity.erasure.receipt.completed");
    retries = meters.counter("identity.erasure.receipt.retries");
    exhausted = meters.counter("identity.erasure.receipt.exhausted");
  }

  @Scheduled(fixedDelayString = "${identity.erasure-receipt-worker-delay:PT1S}")
  public void run() {
    Instant now = clock.instant();
    var item = transactions.required(() -> coordinator.claim(now, LEASE));
    if (item.isEmpty()) return;
    try {
      transactions.required(
          () -> {
            coordinator.apply(item.get(), clock.instant());
            return null;
          });
      completed.increment();
    } catch (RuntimeException failure) {
      int attempt = item.get().attemptCount() + 1;
      if (attempt >= MAX_ATTEMPTS) {
        transactions.required(
            () -> {
              coordinator.exhaust(item.get().eventId(), attempt, failure);
              return null;
            });
        exhausted.increment();
      } else {
        Instant next = clock.instant().plus(backoff(attempt));
        transactions.required(
            () -> {
              coordinator.reschedule(item.get().eventId(), attempt, next, failure);
              return null;
            });
        retries.increment();
      }
    }
  }

  private static Duration backoff(int attempt) {
    return Duration.ofSeconds(Math.min(1800, 1L << Math.min(attempt - 1, 10)));
  }
}
