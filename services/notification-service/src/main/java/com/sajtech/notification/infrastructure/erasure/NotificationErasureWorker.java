package com.sajtech.notification.infrastructure.erasure;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

public final class NotificationErasureWorker {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final int MAX_ATTEMPTS = 48;
  private final IdentityErasureTargetClient identity;
  private final JooqNotificationErasureRepository repository;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final io.micrometer.core.instrument.Counter completed;
  private final io.micrometer.core.instrument.Counter retries;
  private final io.micrometer.core.instrument.Counter exhausted;

  public NotificationErasureWorker(
      IdentityErasureTargetClient identity,
      JooqNotificationErasureRepository repository,
      TransactionTemplate transactions,
      Clock clock,
      MeterRegistry meters) {
    this.identity = identity;
    this.repository = repository;
    this.transactions = transactions;
    this.clock = clock;
    completed = meters.counter("notification.erasure.completed");
    retries = meters.counter("notification.erasure.retries");
    exhausted = meters.counter("notification.erasure.exhausted");
  }

  @Scheduled(fixedDelayString = "${notification.erasure-worker-delay:PT1S}")
  public void run() {
    Instant now = clock.instant();
    var claimed = transactions.execute(status -> repository.claim(now, LEASE));
    if (claimed == null || claimed.isEmpty()) return;
    var item = claimed.get();
    try {
      String token = "";
      boolean completePage;
      do {
        var page =
            identity.resolve(
                item.eventId(), item.erasureRequestId(), item.participantPolicyVersion(), token);
        transactions.executeWithoutResult(ignored -> repository.erasePage(page.notificationIds()));
        token = page.nextPageToken();
        completePage = page.complete();
      } while (!completePage);
      transactions.executeWithoutResult(ignored -> repository.complete(item, clock.instant()));
      completed.increment();
    } catch (RuntimeException exception) {
      fail(item, exception);
    }
  }

  private void fail(JooqNotificationErasureRepository.InboxItem item, RuntimeException failure) {
    int attempt = item.attemptCount() + 1;
    String error = safeError(failure);
    if (attempt >= MAX_ATTEMPTS) {
      transactions.executeWithoutResult(
          ignored -> repository.exhaust(item.eventId(), attempt, error));
      exhausted.increment();
    } else {
      Instant next = clock.instant().plus(backoff(attempt));
      transactions.executeWithoutResult(
          ignored -> repository.reschedule(item.eventId(), attempt, next, error));
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
