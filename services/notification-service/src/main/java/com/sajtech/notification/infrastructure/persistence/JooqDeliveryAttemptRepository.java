package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.port.out.DeliveryAttemptRepository;
import com.sajtech.notification.application.delivery.port.out.DeliveryEscrowReaderPort;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqDeliveryAttemptRepository implements DeliveryAttemptRepository {
  private final DSLContext dsl;
  private final DeliveryEscrowReaderPort escrowReader;

  public JooqDeliveryAttemptRepository(DSLContext dsl, DeliveryEscrowReaderPort escrowReader) {
    this.dsl = dsl;
    this.escrowReader = escrowReader;
  }

  @Override
  public List<ProviderDispatchMessage> claimDue(int batchSize) {
    List<ProviderDispatchMessage> result = new ArrayList<>();
    dsl.transaction(configuration -> {
      DSLContext tx = org.jooq.impl.DSL.using(configuration);
      var rows = tx.fetch("""
          SELECT a.attempt_id, n.notification_id, n.channel
          FROM notification_attempt a
          JOIN notification n ON n.notification_id = a.notification_id
          WHERE a.state IN ('PENDING','RECONCILING')
            AND a.next_action_at <= clock_timestamp()
            AND (a.claimed_until IS NULL OR a.claimed_until < clock_timestamp())
          ORDER BY a.next_action_at
          LIMIT ?
          FOR UPDATE SKIP LOCKED
          """, batchSize);
      for (var row : rows) {
        UUID executionId = UUID.randomUUID();
        tx.execute("""
            UPDATE notification_attempt
            SET state='DISPATCHING', execution_id=?, claimed_until=clock_timestamp() + interval '5 minutes', updated_at=clock_timestamp()
            WHERE attempt_id=?
            """, executionId, row.get("attempt_id"));
        UUID notificationId = row.get("notification_id", UUID.class);
        var payload = escrowReader.decrypt(notificationId, row.get("attempt_id", UUID.class));
        result.add(new ProviderDispatchMessage(notificationId, executionId,
            NotificationChannel.valueOf(row.get("channel", String.class)),
            payload.recipient(), payload.subject(), payload.text(), payload.html()));
      }
    });
    return result;
  }

  @Override
  public void markCompleted(ProviderDispatchMessage message) {
    dsl.execute("""
        UPDATE notification_attempt
        SET state='COMPLETED', updated_at=clock_timestamp(), claimed_until=NULL
        WHERE notification_id=? AND execution_id=?
        """, message.notificationId(), message.executionId());
  }
}
