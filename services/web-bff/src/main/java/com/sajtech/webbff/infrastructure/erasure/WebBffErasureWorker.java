package com.sajtech.webbff.infrastructure.erasure;

import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

public final class WebBffErasureWorker {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final int MAX_ATTEMPTS = 48;
  private final IdentityErasureTargetClient identity;
  private final JooqWebBffErasureRepository repository;
  private final BrowserSessionPort sessions;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final io.micrometer.core.instrument.Counter completed;
  private final io.micrometer.core.instrument.Counter retries;
  private final io.micrometer.core.instrument.Counter exhausted;

  public WebBffErasureWorker(
      IdentityErasureTargetClient identity,
      JooqWebBffErasureRepository repository,
      BrowserSessionPort sessions,
      TransactionTemplate transactions,
      Clock clock,
      MeterRegistry meters) {
    this.identity = identity;
    this.repository = repository;
    this.sessions = sessions;
    this.transactions = transactions;
    this.clock = clock;
    completed = meters.counter("web_bff.erasure.completed");
    retries = meters.counter("web_bff.erasure.retries");
    exhausted = meters.counter("web_bff.erasure.exhausted");
  }

  @Scheduled(fixedDelayString = "${web-bff.erasure-worker-delay:PT1S}")
  public void run() {
    Instant now = clock.instant();
    var claimed = transactions.execute(status -> repository.claim(now, LEASE));
    if (claimed == null || claimed.isEmpty()) return;
    var item = claimed.get();
    try {
      var userId =
          identity.resolve(
              item.eventId(), item.erasureRequestId(), item.participantPolicyVersion());
      sessions.eraseUser(userId);
      transactions.executeWithoutResult(ignored -> repository.complete(item, clock.instant()));
      completed.increment();
    } catch (RuntimeException exception) {
      fail(item, exception);
    }
  }

  private void fail(JooqWebBffErasureRepository.InboxItem item, RuntimeException failure) {
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
