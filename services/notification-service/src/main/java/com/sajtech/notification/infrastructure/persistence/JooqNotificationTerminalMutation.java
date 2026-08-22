package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.util.UUID;
import org.jooq.DSLContext;

final class JooqNotificationTerminalMutation {
  private JooqNotificationTerminalMutation() {}

  static void terminalize(
      DSLContext tx, UUID notificationId, NotificationLifecycle lifecycle, boolean delivered) {
    if (lifecycle == null || !lifecycle.isTerminal()) {
      throw new IllegalArgumentException("Terminal notification lifecycle is required");
    }
    int updated =
        tx.execute(
            """
            UPDATE notification
            SET lifecycle = ?,
                recipient_nonce = NULL,
                recipient_ciphertext = NULL,
                subject_nonce = NULL,
                subject_ciphertext = NULL,
                text_nonce = NULL,
                text_ciphertext = NULL,
                html_nonce = NULL,
                html_ciphertext = NULL,
                updated_at = clock_timestamp()
            WHERE notification_id = ?
              AND lifecycle IN ('ACCEPTED','DISPATCHING','RETRY_WAIT','PROVIDER_ACCEPTED')
            """,
            lifecycle.name(),
            notificationId);
    if (updated != 1) {
      throw new IllegalStateException("Notification terminal transition was not applicable");
    }
    tx.execute(
        """
        INSERT INTO notification_result_outbox(
            outbox_id, notification_id, terminal_lifecycle, occurred_at, next_attempt_at, delivered_at
        ) VALUES (?, ?, ?, clock_timestamp(), clock_timestamp(), CASE WHEN ? THEN clock_timestamp() ELSE NULL END)
        ON CONFLICT (notification_id) DO NOTHING
        """,
        UUID.randomUUID(),
        notificationId,
        lifecycle.name(),
        delivered);
  }
}
