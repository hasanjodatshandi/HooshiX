package com.sajtech.identity.infrastructure.worker;

import static com.sajtech.identity.application.transaction.model.TransactionProfile.WORK_CLAIM;

import com.google.protobuf.Timestamp;
import com.sajtech.identity.application.erasure.model.ErasureCommandOutboxItem;
import com.sajtech.identity.application.erasure.port.out.ErasureCommandOutbox;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.contract.v1.ErasureCommandEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public final class ErasureCommandOutboxDispatcher {
  private static final int BATCH = 25;
  private static final int MAX_ATTEMPTS = 12;
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration SEND_TIMEOUT = Duration.ofSeconds(12);
  private final ErasureCommandOutbox outbox;
  private final KafkaTemplate<String, byte[]> kafka;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final String topic;
  private final Counter published;
  private final Counter retries;
  private final Counter exhausted;

  public ErasureCommandOutboxDispatcher(
      ErasureCommandOutbox outbox,
      KafkaTemplate<String, byte[]> kafka,
      TransactionRunner transactions,
      Clock clock,
      String topic,
      MeterRegistry meters) {
    this.outbox = outbox;
    this.kafka = kafka;
    this.transactions = transactions;
    this.clock = clock;
    this.topic = topic;
    this.published = meters.counter("identity.erasure.outbox.published");
    this.retries = meters.counter("identity.erasure.outbox.retries");
    this.exhausted = meters.counter("identity.erasure.outbox.exhausted");
  }

  @Scheduled(fixedDelayString = "${identity.erasure-dispatch-delay:PT1S}")
  public void dispatch() {
    for (int index = 0; index < BATCH; index++) {
      Instant now = clock.instant();
      var due = transactions.required(WORK_CLAIM, () -> outbox.claimDue(1, now, LEASE));
      if (due.isEmpty()) break;
      if (due.size() != 1) {
        throw new IllegalStateException("Erasure outbox exceeded the single-claim lease");
      }
      publish(due.getFirst());
    }
  }

  private void publish(ErasureCommandOutboxItem item) {
    try {
      ErasureCommandEvent event =
          ErasureCommandEvent.newBuilder()
              .setEventId(item.eventId().toString())
              .setErasureRequestId(item.erasureRequestId().toString())
              .setParticipantPolicyVersion(item.participantPolicyVersion())
              .setOccurredAt(timestamp(item.occurredAt()))
              .build();
      kafka
          .send(topic, item.erasureRequestId().toString(), event.toByteArray())
          .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      Instant completedAt = clock.instant();
      transactions.required(
          () -> {
            outbox.markPublished(item.eventId(), completedAt);
            return null;
          });
      published.increment();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      fail(item, "INTERRUPTED");
    } catch (Exception exception) {
      fail(item, safeError(exception));
    }
  }

  private void fail(ErasureCommandOutboxItem item, String error) {
    int attempt = item.attemptCount() + 1;
    Instant now = clock.instant();
    transactions.required(
        () -> {
          if (attempt >= MAX_ATTEMPTS) {
            outbox.markExhausted(item.eventId(), attempt, now, error);
          } else {
            outbox.reschedule(item.eventId(), attempt, now.plus(backoff(attempt)), now, error);
          }
          return null;
        });
    if (attempt >= MAX_ATTEMPTS) exhausted.increment();
    else retries.increment();
  }

  private static Duration backoff(int attempt) {
    long seconds = Math.min(300, 1L << Math.min(attempt - 1, 8));
    return Duration.ofSeconds(seconds);
  }

  private static String safeError(Exception exception) {
    Throwable root = exception;
    while (root.getCause() != null) root = root.getCause();
    String value = root.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    value = value.replaceAll("[^A-Z0-9_]", "_");
    if (value.isEmpty() || !Character.isLetter(value.charAt(0))) value = "DEPENDENCY_FAILURE";
    return value.substring(0, Math.min(64, value.length()));
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }
}
