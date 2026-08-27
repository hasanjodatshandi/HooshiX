package com.sajtech.notification.infrastructure.erasure;

import com.google.protobuf.Timestamp;
import com.sajtech.identity.contract.v1.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

public final class NotificationErasureReceiptDispatcher {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final int MAX_ATTEMPTS = 12;
  private final DSLContext dsl;
  private final KafkaTemplate<String, byte[]> kafka;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final String topic;
  private final io.micrometer.core.instrument.Counter published;
  private final io.micrometer.core.instrument.Counter failures;

  public NotificationErasureReceiptDispatcher(
      DSLContext dsl,
      KafkaTemplate<String, byte[]> kafka,
      TransactionTemplate transactions,
      Clock clock,
      String topic,
      MeterRegistry meters) {
    this.dsl = dsl;
    this.kafka = kafka;
    this.transactions = transactions;
    this.clock = clock;
    this.topic = topic;
    published = meters.counter("notification.erasure.receipt_outbox.published");
    failures = meters.counter("notification.erasure.receipt_outbox.failures");
  }

  @Scheduled(fixedDelayString = "${notification.erasure-receipt-dispatch-delay:PT1S}")
  public void dispatch() {
    Instant now = clock.instant();
    Optional<Item> claimed = transactions.execute(status -> claim(now));
    if (claimed == null || claimed.isEmpty()) return;
    Item item = claimed.get();
    try {
      ErasureReceiptEvent event =
          ErasureReceiptEvent.newBuilder()
              .setEventId(item.eventId().toString())
              .setErasureRequestId(item.requestId().toString())
              .setParticipant(ErasureParticipant.ERASURE_PARTICIPANT_NOTIFICATION_SERVICE)
              .setParticipantPolicyVersion(item.policy())
              .setOutcome(ErasureParticipantOutcome.ERASURE_PARTICIPANT_OUTCOME_COMPLETED)
              .addAllActionCategories(Arrays.asList(item.actions().split(",")))
              .setOccurredAt(timestamp(item.occurredAt()))
              .build();
      kafka.send(topic, item.requestId().toString(), event.toByteArray()).get(12, TimeUnit.SECONDS);
      Instant done = clock.instant();
      transactions.executeWithoutResult(
          ignored ->
              dsl.execute(
                  "UPDATE notification_erasure_receipt_outbox SET state='PUBLISHED',published_at=CAST(? AS TIMESTAMP WITH TIME ZONE),lease_until=NULL,last_error_class=NULL,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=? AND state='DISPATCHING'",
                  ts(done),
                  ts(done),
                  item.eventId()));
      published.increment();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      fail(item, exception);
    } catch (Exception exception) {
      fail(item, exception);
    }
  }

  private Optional<Item> claim(Instant now) {
    return dsl.fetchOptional(
            "SELECT event_id,erasure_request_id,participant_policy_version,action_categories,attempt_count,occurred_at FROM notification_erasure_receipt_outbox WHERE state IN ('PENDING','DISPATCHING') AND next_attempt_at<=CAST(? AS TIMESTAMP WITH TIME ZONE) AND (lease_until IS NULL OR lease_until<=CAST(? AS TIMESTAMP WITH TIME ZONE)) ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED",
            ts(now),
            ts(now))
        .map(
            row -> {
              UUID id = row.get("event_id", UUID.class);
              dsl.execute(
                  "UPDATE notification_erasure_receipt_outbox SET state='DISPATCHING',lease_until=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=?",
                  ts(now.plus(LEASE)),
                  ts(now),
                  id);
              return new Item(
                  id,
                  row.get("erasure_request_id", UUID.class),
                  row.get("participant_policy_version", String.class),
                  row.get("action_categories", String.class),
                  row.get("attempt_count", Integer.class),
                  row.get("occurred_at", OffsetDateTime.class).toInstant());
            });
  }

  private void fail(Item item, Exception failure) {
    int attempt = item.attemptCount() + 1;
    Instant now = clock.instant();
    String error = safeError(failure);
    transactions.executeWithoutResult(
        ignored -> {
          if (attempt >= MAX_ATTEMPTS)
            dsl.execute(
                "UPDATE notification_erasure_receipt_outbox SET state='EXHAUSTED',attempt_count=?,lease_until=NULL,last_error_class=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=? AND state='DISPATCHING'",
                attempt,
                error,
                ts(now),
                item.eventId());
          else
            dsl.execute(
                "UPDATE notification_erasure_receipt_outbox SET state='PENDING',attempt_count=?,next_attempt_at=CAST(? AS TIMESTAMP WITH TIME ZONE),lease_until=NULL,last_error_class=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=? AND state='DISPATCHING'",
                attempt,
                ts(now.plusSeconds(Math.min(300, 1L << Math.min(attempt - 1, 8)))),
                error,
                ts(now),
                item.eventId());
        });
    failures.increment();
  }

  private static String safeError(Throwable failure) {
    String v =
        failure.getClass().getSimpleName().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    return v.substring(0, Math.min(64, v.length()));
  }

  private static Timestamp timestamp(Instant v) {
    return Timestamp.newBuilder().setSeconds(v.getEpochSecond()).setNanos(v.getNano()).build();
  }

  private static OffsetDateTime ts(Instant v) {
    return v.atOffset(ZoneOffset.UTC);
  }

  private record Item(
      UUID eventId,
      UUID requestId,
      String policy,
      String actions,
      int attemptCount,
      Instant occurredAt) {}
}
