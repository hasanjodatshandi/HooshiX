package com.sajtech.identity.infrastructure.worker;

import static com.sajtech.identity.application.transaction.model.TransactionProfile.WORK_CLAIM;

import com.google.protobuf.Timestamp;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.contract.v1.TenantLifecycleEvent;
import com.sajtech.identity.contract.v1.TenantLifecycleEventState;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public final class TenantLifecycleEventDispatcher {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final int MAX_ATTEMPTS = 12;
  private final DSLContext dsl;
  private final KafkaTemplate<String, byte[]> kafka;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final String topic;
  private final io.micrometer.core.instrument.Counter published;
  private final io.micrometer.core.instrument.Counter failures;

  public TenantLifecycleEventDispatcher(
      DSLContext dsl,
      KafkaTemplate<String, byte[]> kafka,
      TransactionRunner transactions,
      Clock clock,
      String topic,
      MeterRegistry meters) {
    this.dsl = dsl;
    this.kafka = kafka;
    this.transactions = transactions;
    this.clock = clock;
    this.topic = topic;
    this.published = meters.counter("identity.tenant.lifecycle_outbox.published");
    this.failures = meters.counter("identity.tenant.lifecycle_outbox.failures");
  }

  @Scheduled(fixedDelayString = "${identity.tenant-lifecycle-dispatch-delay:PT1S}")
  public void dispatch() {
    Instant now = clock.instant();
    Optional<Item> claimed = transactions.required(WORK_CLAIM, () -> claim(now));
    if (claimed.isEmpty()) return;
    Item item = claimed.get();
    try {
      TenantLifecycleEvent event =
          TenantLifecycleEvent.newBuilder()
              .setEventId(item.eventId().toString())
              .setTenantId(item.tenantId().toString())
              .setLifecycleVersion(item.version())
              .setState(
                  TenantLifecycleEventState.valueOf(
                      "TENANT_LIFECYCLE_EVENT_STATE_" + item.lifecycle()))
              .setOccurredAt(timestamp(item.occurredAt()))
              .build();
      kafka.send(topic, item.tenantId().toString(), event.toByteArray()).get(12, TimeUnit.SECONDS);
      Instant done = clock.instant();
      transactions.required(
          () -> {
            dsl.execute(
                "UPDATE identity_tenant_lifecycle_event_outbox SET state='PUBLISHED',published_at=CAST(? AS TIMESTAMP WITH TIME ZONE),lease_until=NULL,last_error_class=NULL,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=? AND state='DISPATCHING'",
                ts(done),
                ts(done),
                item.eventId());
            return null;
          });
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
            "SELECT event_id,tenant_id,lifecycle_version,lifecycle_state,attempt_count,occurred_at FROM identity_tenant_lifecycle_event_outbox WHERE state IN ('PENDING','DISPATCHING') AND next_attempt_at<=CAST(? AS TIMESTAMP WITH TIME ZONE) AND (lease_until IS NULL OR lease_until<=CAST(? AS TIMESTAMP WITH TIME ZONE)) ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED",
            ts(now),
            ts(now))
        .map(
            row -> {
              UUID id = row.get("event_id", UUID.class);
              dsl.execute(
                  "UPDATE identity_tenant_lifecycle_event_outbox SET state='DISPATCHING',lease_until=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=?",
                  ts(now.plus(LEASE)),
                  ts(now),
                  id);
              return new Item(
                  id,
                  row.get("tenant_id", UUID.class),
                  row.get("lifecycle_version", Long.class),
                  row.get("lifecycle_state", String.class),
                  row.get("attempt_count", Integer.class),
                  row.get("occurred_at", OffsetDateTime.class).toInstant());
            });
  }

  private void fail(Item item, Exception failure) {
    int attempt = item.attemptCount() + 1;
    Instant now = clock.instant();
    String error = safeError(failure);
    transactions.required(
        () -> {
          if (attempt >= MAX_ATTEMPTS) {
            dsl.execute(
                "UPDATE identity_tenant_lifecycle_event_outbox SET state='EXHAUSTED',attempt_count=?,lease_until=NULL,last_error_class=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=? AND state='DISPATCHING'",
                attempt,
                error,
                ts(now),
                item.eventId());
          } else {
            dsl.execute(
                "UPDATE identity_tenant_lifecycle_event_outbox SET state='PENDING',attempt_count=?,next_attempt_at=CAST(? AS TIMESTAMP WITH TIME ZONE),lease_until=NULL,last_error_class=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=? AND state='DISPATCHING'",
                attempt,
                ts(now.plusSeconds(Math.min(300, 1L << Math.min(attempt - 1, 8)))),
                error,
                ts(now),
                item.eventId());
          }
          return null;
        });
    failures.increment();
  }

  private static String safeError(Throwable failure) {
    String value =
        failure.getClass().getSimpleName().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    if (value.isEmpty()) value = "PUBLISH_FAILURE";
    return value.substring(0, Math.min(64, value.length()));
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static OffsetDateTime ts(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private record Item(
      UUID eventId,
      UUID tenantId,
      long version,
      String lifecycle,
      int attemptCount,
      Instant occurredAt) {}
}
