package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.erasure.model.ErasureCommandOutboxItem;
import com.sajtech.identity.application.erasure.port.out.ErasureCommandOutbox;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqErasureCommandOutbox implements ErasureCommandOutbox {
  private final DSLContext dsl;

  public JooqErasureCommandOutbox(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<ErasureCommandOutboxItem> claimDue(int batchSize, Instant now, Duration lease) {
    if (batchSize < 1 || batchSize > 100 || lease == null || lease.isNegative() || lease.isZero()) {
      throw new IllegalArgumentException("Erasure outbox claim configuration is invalid");
    }
    return dsl.fetch(
            """
            SELECT event_id,erasure_request_id,participant_policy_version,
                   attempt_count,occurred_at
            FROM identity_erasure_event_outbox
            WHERE event_type='COMMAND'
              AND state IN ('PENDING','DISPATCHING')
              AND next_attempt_at<=CAST(? AS TIMESTAMP WITH TIME ZONE)
              AND (lease_until IS NULL OR lease_until<=CAST(? AS TIMESTAMP WITH TIME ZONE))
            ORDER BY next_attempt_at,event_id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """,
            ts(now),
            ts(now),
            batchSize)
        .map(
            row -> {
              UUID eventId = row.get("event_id", UUID.class);
              int changed =
                  dsl.execute(
                      """
                      UPDATE identity_erasure_event_outbox
                      SET state='DISPATCHING',lease_until=CAST(? AS TIMESTAMP WITH TIME ZONE),
                          updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
                      WHERE event_id=? AND state IN ('PENDING','DISPATCHING')
                      """,
                      ts(now.plus(lease)),
                      ts(now),
                      eventId);
              if (changed != 1) throw new IllegalStateException("Erasure outbox claim failed");
              return new ErasureCommandOutboxItem(
                  eventId,
                  row.get("erasure_request_id", UUID.class),
                  row.get("participant_policy_version", String.class),
                  row.get("attempt_count", Integer.class),
                  row.get("occurred_at", OffsetDateTime.class).toInstant());
            });
  }

  @Override
  public void markPublished(UUID eventId, Instant now) {
    int changed =
        dsl.execute(
            """
            UPDATE identity_erasure_event_outbox
            SET state='PUBLISHED',published_at=CAST(? AS TIMESTAMP WITH TIME ZONE),
                lease_until=NULL,last_error_class=NULL,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE event_id=? AND state='DISPATCHING'
            """,
            ts(now),
            ts(now),
            eventId);
    if (changed != 1) throw new IllegalStateException("Erasure outbox completion failed");
  }

  @Override
  public void reschedule(
      UUID eventId, int attemptCount, Instant nextAttempt, Instant now, String safeErrorClass) {
    requireRetry(attemptCount, nextAttempt, now, safeErrorClass);
    int changed =
        dsl.execute(
            """
            UPDATE identity_erasure_event_outbox
            SET state='PENDING',attempt_count=?,next_attempt_at=CAST(? AS TIMESTAMP WITH TIME ZONE),
                lease_until=NULL,last_error_class=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE event_id=? AND state='DISPATCHING'
            """,
            attemptCount,
            ts(nextAttempt),
            safeErrorClass,
            ts(now),
            eventId);
    if (changed != 1) throw new IllegalStateException("Erasure outbox retry failed");
  }

  @Override
  public void markExhausted(UUID eventId, int attemptCount, Instant now, String safeErrorClass) {
    requireRetry(attemptCount, now, now, safeErrorClass);
    int changed =
        dsl.execute(
            """
            UPDATE identity_erasure_event_outbox
            SET state='EXHAUSTED',attempt_count=?,lease_until=NULL,last_error_class=?,
                updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE event_id=? AND state='DISPATCHING'
            """,
            attemptCount,
            safeErrorClass,
            ts(now),
            eventId);
    if (changed != 1) throw new IllegalStateException("Erasure outbox exhaustion failed");
  }

  private static void requireRetry(
      int attemptCount, Instant nextAttempt, Instant now, String safeErrorClass) {
    if (attemptCount < 1
        || nextAttempt == null
        || now == null
        || nextAttempt.isBefore(now)
        || safeErrorClass == null
        || !safeErrorClass.matches("[A-Z][A-Z0-9_]{0,63}")) {
      throw new IllegalArgumentException("Erasure outbox retry metadata is invalid");
    }
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
