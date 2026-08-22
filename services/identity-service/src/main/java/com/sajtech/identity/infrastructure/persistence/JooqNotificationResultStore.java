package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.notification.model.*;
import com.sajtech.identity.application.notification.port.out.NotificationResultStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

public final class JooqNotificationResultStore implements NotificationResultStore {
  private final DSLContext dsl;

  public JooqNotificationResultStore(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public NotificationResultApplyOutcome apply(NotificationTerminalResult result) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          Record row =
              tx.fetchOne(
                  "SELECT notification_terminal_lifecycle FROM identity_notification_outbox WHERE notification_id=? FOR UPDATE",
                  result.notificationId());
          if (row == null) return NotificationResultApplyOutcome.NOT_FOUND;
          String existing = row.get("notification_terminal_lifecycle", String.class);
          if (existing != null) {
            return existing.equals(result.lifecycle().name())
                ? NotificationResultApplyOutcome.REPLAY
                : NotificationResultApplyOutcome.CONFLICT;
          }
          int changed =
              tx.execute(
                  "UPDATE identity_notification_outbox SET notification_terminal_lifecycle=?,notification_result_at=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=clock_timestamp() WHERE notification_id=? AND notification_terminal_lifecycle IS NULL",
                  result.lifecycle().name(),
                  OffsetDateTime.ofInstant(result.occurredAt(), ZoneOffset.UTC),
                  result.notificationId());
          if (changed != 1) throw new IllegalStateException("Notification result update failed");
          return NotificationResultApplyOutcome.APPLIED;
        });
  }
}
