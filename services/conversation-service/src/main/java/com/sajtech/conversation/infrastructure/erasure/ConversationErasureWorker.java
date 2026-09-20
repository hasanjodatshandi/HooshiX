package com.sajtech.conversation.infrastructure.erasure;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.scheduling.annotation.Scheduled;

public final class ConversationErasureWorker {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final int MAX_ATTEMPTS = 48;
  private final IdentityErasureTargetClient identity;
  private final JdbcConversationErasureRepository repository;
  private final Clock clock;
  private final io.micrometer.core.instrument.Counter completed;
  private final io.micrometer.core.instrument.Counter retries;
  private final io.micrometer.core.instrument.Counter exhausted;

  public ConversationErasureWorker(
      IdentityErasureTargetClient identity,
      JdbcConversationErasureRepository repository,
      Clock clock,
      MeterRegistry meters) {
    this.identity = identity;
    this.repository = repository;
    this.clock = clock;
    this.completed = meters.counter("conversation.erasure.completed");
    this.retries = meters.counter("conversation.erasure.retries");
    this.exhausted = meters.counter("conversation.erasure.exhausted");
  }

  @Scheduled(fixedDelayString = "${conversation.erasure-worker-delay:PT1S}")
  public void run() {
    var claimed = repository.claim(clock.instant(), LEASE);
    if (claimed.isEmpty()) return;
    var item = claimed.get();
    try {
      var userId =
          identity.resolve(
              item.eventId(), item.erasureRequestId(), item.participantPolicyVersion());
      repository.eraseSubject(userId, clock.instant());
      repository.complete(item, clock.instant());
      completed.increment();
    } catch (RuntimeException exception) {
      fail(item, exception);
    }
  }

  private void fail(JdbcConversationErasureRepository.InboxItem item, RuntimeException failure) {
    int attempt = item.attemptCount() + 1;
    boolean isExhausted = attempt >= MAX_ATTEMPTS;
    Instant next = clock.instant().plus(backoff(attempt));
    repository.reschedule(item.eventId(), attempt, next, safeError(failure), isExhausted);
    if (isExhausted) exhausted.increment();
    else retries.increment();
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
