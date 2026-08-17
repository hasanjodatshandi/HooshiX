package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.submit.model.AcceptedNotificationWrite;
import com.sajtech.notification.application.submit.model.EncryptedField;
import com.sajtech.notification.application.submit.model.StoredAcceptedNotification;
import com.sajtech.notification.application.submit.port.out.DuplicateNotificationRequestException;
import com.sajtech.notification.application.submit.port.out.NotificationAcceptanceRepository;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;

public final class JooqNotificationAcceptanceRepository
    implements NotificationAcceptanceRepository {
  private final DSLContext dsl;

  public JooqNotificationAcceptanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<StoredAcceptedNotification> findByCallerAndRequestId(
      String callerService, UUID requestId) {
    return dsl.fetchOptional(
            """
            SELECT notification_id, intent_fingerprint, fingerprint_version,
                   fingerprint_key_id, lifecycle, accepted_at
            FROM notification
            WHERE caller_service = ? AND request_id = ?
            """,
            callerService,
            requestId)
        .map(this::mapStored);
  }

  @Override
  public void insert(AcceptedNotificationWrite write) {
    EncryptedField subject = write.deliveryPayload().subject();
    EncryptedField html = write.deliveryPayload().html();
    try {
      dsl.execute(
          """
          INSERT INTO notification(
              notification_id, caller_service, request_id, intent_fingerprint,
              fingerprint_version, fingerprint_key_id, channel, semantic_type, locale,
              template_version_id, template_sha256, message_not_after,
              effective_delivery_deadline, lifecycle, escrow_format_version, escrow_key_id,
              recipient_nonce, recipient_ciphertext, subject_nonce, subject_ciphertext,
              text_nonce, text_ciphertext, html_nonce, html_ciphertext,
              accepted_at, sensitive_expires_at, updated_at
          ) VALUES (
              ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
          )
          """,
          write.notificationId(),
          write.intent().callerService(),
          write.intent().requestId(),
          write.fingerprint().value(),
          write.fingerprint().version(),
          write.fingerprint().keyId(),
          write.intent().channel().name(),
          write.intent().semanticType().name(),
          write.intent().locale(),
          write.template().versionId(),
          write.template().contentSha256(),
          timestamp(write.intent().messageNotAfter()),
          timestamp(write.effectiveDeliveryDeadline()),
          write.lifecycle().name(),
          write.deliveryPayload().formatVersion(),
          write.deliveryPayload().keyId(),
          write.deliveryPayload().recipient().nonce(),
          write.deliveryPayload().recipient().ciphertext(),
          nullableNonce(subject),
          nullableCiphertext(subject),
          write.deliveryPayload().text().nonce(),
          write.deliveryPayload().text().ciphertext(),
          nullableNonce(html),
          nullableCiphertext(html),
          timestamp(write.acceptedAt()),
          timestamp(write.sensitiveExpiresAt()),
          timestamp(write.acceptedAt()));
      dsl.execute(
          """
          INSERT INTO notification_attempt(
              attempt_id, notification_id, attempt_number, state, next_action_at
          ) VALUES (?, ?, 1, 'PENDING', ?)
          """,
          UUID.randomUUID(),
          write.notificationId(),
          timestamp(write.acceptedAt()));
    } catch (DataAccessException exception) {
      if ("23505".equals(exception.sqlState())) {
        throw new DuplicateNotificationRequestException(exception);
      }
      throw exception;
    }
  }

  private StoredAcceptedNotification mapStored(Record record) {
    return new StoredAcceptedNotification(
        record.get("notification_id", UUID.class),
        record.get("intent_fingerprint", byte[].class),
        record.get("fingerprint_version", String.class),
        record.get("fingerprint_key_id", String.class),
        NotificationLifecycle.valueOf(record.get("lifecycle", String.class)),
        record.get("accepted_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime timestamp(java.time.Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static byte[] nullableNonce(EncryptedField field) {
    return field == null ? null : field.nonce();
  }

  private static byte[] nullableCiphertext(EncryptedField field) {
    return field == null ? null : field.ciphertext();
  }
}
