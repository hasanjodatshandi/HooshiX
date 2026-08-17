package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.submit.model.AcceptedNotificationWrite;
import com.sajtech.notification.application.submit.model.EncryptedDeliveryPayload;
import com.sajtech.notification.application.submit.model.StoredAcceptedNotification;
import com.sajtech.notification.application.submit.port.out.DuplicateNotificationRequestException;
import com.sajtech.notification.application.submit.port.out.NotificationAcceptanceRepository;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;

public final class JooqNotificationAcceptanceRepository implements NotificationAcceptanceRepository {
  private final DSLContext dsl;

  public JooqNotificationAcceptanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<StoredAcceptedNotification> findByCallerAndRequestId(
      String callerService, UUID requestId) {
    Record row =
        dsl.fetchOne(
            """
            select notification_id, request_fingerprint, fingerprint_version,
                   fingerprint_key_id, lifecycle, accepted_at
              from notification_delivery
             where caller_service = ?
               and request_id = ?
            """,
            callerService,
            requestId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new StoredAcceptedNotification(
            row.get("notification_id", UUID.class),
            row.get("request_fingerprint", byte[].class),
            row.get("fingerprint_version", String.class),
            row.get("fingerprint_key_id", String.class),
            NotificationLifecycle.valueOf(row.get("lifecycle", String.class)),
            row.get("accepted_at", java.time.OffsetDateTime.class).toInstant()));
  }

  @Override
  public void insert(AcceptedNotificationWrite write) {
    EncryptedDeliveryPayload encrypted = write.encryptedPayload();
    try {
      dsl.execute(
          """
          insert into notification_delivery (
            notification_id, caller_service, request_id, channel, recipient_ciphertext,
            recipient_nonce, locale, semantic_type, request_fingerprint,
            fingerprint_version, fingerprint_key_id, template_id, template_version,
            template_content_digest, subject_ciphertext, subject_nonce, text_ciphertext,
            text_nonce, html_ciphertext, html_nonce, delivery_key_version, delivery_key_id,
            lifecycle, accepted_at, effective_delivery_deadline, sensitive_delete_after
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          write.notificationId(),
          write.intent().callerService(),
          write.intent().requestId(),
          write.intent().channel().name(),
          encrypted.recipient().ciphertext(),
          encrypted.recipient().nonce(),
          write.intent().locale(),
          write.intent().semanticType().name(),
          write.fingerprint().value(),
          write.fingerprint().version(),
          write.fingerprint().keyId(),
          write.template().templateId(),
          write.template().version(),
          write.template().contentDigest(),
          encrypted.subject() == null ? null : encrypted.subject().ciphertext(),
          encrypted.subject() == null ? null : encrypted.subject().nonce(),
          encrypted.text().ciphertext(),
          encrypted.text().nonce(),
          encrypted.html() == null ? null : encrypted.html().ciphertext(),
          encrypted.html() == null ? null : encrypted.html().nonce(),
          encrypted.formatVersion(),
          encrypted.keyId(),
          write.lifecycle().name(),
          write.acceptedAt(),
          write.effectiveDeliveryDeadline(),
          write.sensitiveDeleteAfter());
    } catch (DataIntegrityViolationException conflict) {
      if (isCallerRequestConflict(conflict)) {
        throw new DuplicateNotificationRequestException(conflict);
      }
      throw conflict;
    }
  }

  private static boolean isCallerRequestConflict(DataIntegrityViolationException conflict) {
    Throwable cause = conflict.getRootCause();
    if (cause instanceof SQLException sqlException) {
      return "23505".equals(sqlException.getSQLState());
    }
    return false;
  }
}
