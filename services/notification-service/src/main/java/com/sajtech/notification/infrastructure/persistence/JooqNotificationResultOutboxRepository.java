package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.result.model.NotificationResultOutboxRecord;
import com.sajtech.notification.application.result.port.out.NotificationResultOutboxRepository;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

@SuppressWarnings("EI_EXPOSE_REP2")
public final class JooqNotificationResultOutboxRepository
    implements NotificationResultOutboxRepository {
  private final DSLContext dsl;

  public JooqNotificationResultOutboxRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<NotificationResultOutboxRecord> claimDue(int batchSize, Duration lease) {
    if (batchSize <= 0 || batchSize > 25 || lease == null || lease.isZero() || lease.isNegative())
      throw new IllegalArgumentException("Result outbox claim configuration is invalid");
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          tx.execute("SET LOCAL lock_timeout = '100ms'");
          tx.execute("SET LOCAL statement_timeout = '500ms'");
          var rows =
              tx.fetch(
                  """
          SELECT outbox_id,notification_id,terminal_lifecycle,occurred_at,attempt_count
          FROM notification_result_outbox
          WHERE completed_at IS NULL AND exhausted_at IS NULL AND next_attempt_at<=clock_timestamp()
          ORDER BY next_attempt_at,outbox_id LIMIT ? FOR UPDATE SKIP LOCKED
          """,
                  batchSize);
          List<NotificationResultOutboxRecord> result = new ArrayList<>();
          for (Record row : rows) {
            UUID id = row.get("outbox_id", UUID.class);
            if (tx.execute(
                    "UPDATE notification_result_outbox SET next_attempt_at=clock_timestamp()+(?*interval '1 millisecond') WHERE outbox_id=? AND completed_at IS NULL AND exhausted_at IS NULL",
                    lease.toMillis(),
                    id)
                != 1) throw new IllegalStateException("Result outbox claim failed");
            result.add(
                new NotificationResultOutboxRecord(
                    id,
                    row.get("notification_id", UUID.class),
                    NotificationLifecycle.valueOf(row.get("terminal_lifecycle", String.class)),
                    row.get("occurred_at", OffsetDateTime.class).toInstant(),
                    row.get("attempt_count", Integer.class)));
          }
          return List.copyOf(result);
        });
  }

  @Override
  public void markCompleted(UUID outboxId) {
    if (dsl.execute(
            "UPDATE notification_result_outbox SET completed_at=clock_timestamp(),last_error_class=NULL WHERE outbox_id=? AND completed_at IS NULL AND exhausted_at IS NULL",
            outboxId)
        != 1) throw new IllegalStateException("Result outbox completion failed");
  }

  @Override
  public void reschedule(UUID outboxId, int attemptCount, Duration delay, String safeErrorClass) {
    validate(delay, safeErrorClass);
    if (dsl.execute(
            "UPDATE notification_result_outbox SET attempt_count=?,next_attempt_at=clock_timestamp()+(?*interval '1 millisecond'),last_error_class=? WHERE outbox_id=? AND completed_at IS NULL AND exhausted_at IS NULL",
            attemptCount,
            delay.toMillis(),
            safeErrorClass,
            outboxId)
        != 1) throw new IllegalStateException("Result outbox reschedule failed");
  }

  @Override
  public void markExhausted(UUID outboxId, int attemptCount, String safeErrorClass) {
    validate(Duration.ofMillis(1), safeErrorClass);
    if (dsl.execute(
            "UPDATE notification_result_outbox SET attempt_count=?,exhausted_at=clock_timestamp(),last_error_class=? WHERE outbox_id=? AND completed_at IS NULL AND exhausted_at IS NULL",
            attemptCount,
            safeErrorClass,
            outboxId)
        != 1) throw new IllegalStateException("Result outbox exhaustion failed");
  }

  private static void validate(Duration delay, String safeErrorClass) {
    if (delay == null
        || delay.isZero()
        || delay.isNegative()
        || safeErrorClass == null
        || safeErrorClass.isBlank()
        || safeErrorClass.length() > 64
        || safeErrorClass.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Result outbox retry metadata is invalid");
  }
}
