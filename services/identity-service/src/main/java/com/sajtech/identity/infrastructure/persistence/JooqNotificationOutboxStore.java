package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.notification.model.NotificationOutboxRecord;
import com.sajtech.identity.application.notification.port.out.NotificationOutboxStore;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqNotificationOutboxStore implements NotificationOutboxStore {
  private final DSLContext dsl;

  public JooqNotificationOutboxStore(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<NotificationOutboxRecord> claimDue(Instant now, int batch, Duration lease) {
    if (batch <= 0 || batch > 32) throw new IllegalArgumentException("Outbox batch is invalid");
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = org.jooq.impl.DSL.using(configuration);
          var rows =
              tx.fetch(
                  """
        SELECT outbox_id,request_id,channel,locale,escrow_key_id,payload_nonce,payload_ciphertext,message_not_after,attempt_count
        FROM identity_notification_outbox
        WHERE payload_ciphertext IS NOT NULL AND next_attempt_at <= CAST(? AS TIMESTAMP WITH TIME ZONE)
          AND (state='PENDING' OR (state='CLAIMED' AND claimed_until <= CAST(? AS TIMESTAMP WITH TIME ZONE)))
        ORDER BY next_attempt_at,outbox_id FOR UPDATE SKIP LOCKED LIMIT ?
        """,
                  ts(now),
                  ts(now),
                  batch);
          List<NotificationOutboxRecord> result = new ArrayList<>(rows.size());
          for (Record r : rows) {
            UUID id = r.get("outbox_id", UUID.class);
            tx.execute(
                "UPDATE identity_notification_outbox SET state='CLAIMED',claimed_until=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE outbox_id=?",
                ts(now.plus(lease)),
                ts(now),
                id);
            result.add(map(r));
          }
          return List.copyOf(result);
        });
  }

  @Override
  public void markSubmitted(UUID id, UUID notificationId, Instant now) {
    dsl.execute(
        "UPDATE identity_notification_outbox SET state='SUBMITTED',notification_id=?,submitted_at=CAST(? AS TIMESTAMP WITH TIME ZONE),claimed_until=NULL,payload_nonce=NULL,payload_ciphertext=NULL,last_error_class=NULL,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE outbox_id=?",
        notificationId,
        ts(now),
        ts(now),
        id);
  }

  @Override
  public void reschedule(UUID id, int attempts, Instant next, Instant now, String error) {
    dsl.execute(
        "UPDATE identity_notification_outbox SET state='PENDING',attempt_count=?,next_attempt_at=CAST(? AS TIMESTAMP WITH TIME ZONE),claimed_until=NULL,last_error_class=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE outbox_id=?",
        attempts,
        ts(next),
        error,
        ts(now),
        id);
  }

  @Override
  public void markPermanentFailure(UUID id, Instant now, String error) {
    dsl.execute(
        "UPDATE identity_notification_outbox SET state='FAILED_PERMANENT',claimed_until=NULL,payload_nonce=NULL,payload_ciphertext=NULL,last_error_class=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE outbox_id=?",
        error,
        ts(now),
        id);
  }

  @Override
  public int eraseExpiredSensitive(Instant now, int batch) {
    if (batch <= 0 || batch > 256) {
      throw new IllegalArgumentException("Sensitive-retention batch is invalid");
    }
    return dsl.execute(
        """
        WITH due AS (
          SELECT outbox_id
          FROM identity_notification_outbox
          WHERE payload_ciphertext IS NOT NULL
            AND sensitive_expires_at <= CAST(? AS TIMESTAMP WITH TIME ZONE)
          ORDER BY sensitive_expires_at, outbox_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE identity_notification_outbox o
        SET payload_nonce = NULL,
            payload_ciphertext = NULL,
            state = CASE WHEN o.state IN ('PENDING', 'CLAIMED') THEN 'FAILED_PERMANENT' ELSE o.state END,
            claimed_until = NULL,
            last_error_class = CASE WHEN o.state IN ('PENDING', 'CLAIMED') THEN 'SENSITIVE_RETENTION_EXPIRED' ELSE o.last_error_class END,
            updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
        FROM due
        WHERE o.outbox_id = due.outbox_id
        """,
        ts(now),
        batch,
        ts(now));
  }

  private static NotificationOutboxRecord map(Record r) {
    return new NotificationOutboxRecord(
        r.get("outbox_id", UUID.class),
        r.get("request_id", UUID.class),
        "EMAIL".equals(r.get("channel", String.class))
            ? RegistrationChannel.EMAIL
            : RegistrationChannel.PHONE,
        RegistrationLocale.valueOf(r.get("locale", String.class).toUpperCase(Locale.ROOT)),
        r.get("escrow_key_id", String.class),
        r.get("payload_nonce", byte[].class),
        r.get("payload_ciphertext", byte[].class),
        r.get("message_not_after", OffsetDateTime.class).toInstant(),
        r.get("attempt_count", Integer.class));
  }

  private static OffsetDateTime ts(Instant v) {
    return OffsetDateTime.ofInstant(v, ZoneOffset.UTC);
  }
}
