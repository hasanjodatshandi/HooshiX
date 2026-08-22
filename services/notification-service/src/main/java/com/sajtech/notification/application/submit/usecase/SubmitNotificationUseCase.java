package com.sajtech.notification.application.submit.usecase;

import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.AcceptedNotificationWrite;
import com.sajtech.notification.application.submit.model.CanonicalNotificationIntent;
import com.sajtech.notification.application.submit.model.EncryptedDeliveryPayload;
import com.sajtech.notification.application.submit.model.FingerprintDigest;
import com.sajtech.notification.application.submit.model.StoredAcceptedNotification;
import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;
import com.sajtech.notification.application.submit.model.SubmitNotificationResult;
import com.sajtech.notification.application.submit.port.in.SubmitNotification;
import com.sajtech.notification.application.submit.port.out.DatabaseTimePort;
import com.sajtech.notification.application.submit.port.out.DeliveryEscrowPort;
import com.sajtech.notification.application.submit.port.out.DuplicateNotificationRequestException;
import com.sajtech.notification.application.submit.port.out.IntentFingerprintPort;
import com.sajtech.notification.application.submit.port.out.NotificationAcceptanceRepository;
import com.sajtech.notification.application.submit.port.out.NotificationTemplateCatalog;
import com.sajtech.notification.application.submit.port.out.TransactionRunner;
import com.sajtech.notification.application.submit.service.FingerprintMaterialEncoder;
import com.sajtech.notification.application.submit.service.NotificationIntentFactory;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.application.template.model.RenderedNotification;
import com.sajtech.notification.application.template.service.BoundedTemplateRenderer;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class SubmitNotificationUseCase implements SubmitNotification {
  private static final Duration MAX_SENSITIVE_RETENTION = Duration.ofHours(24);

  private final NotificationIntentFactory intentFactory;
  private final FingerprintMaterialEncoder fingerprintEncoder;
  private final IntentFingerprintPort fingerprintPort;
  private final TransactionRunner transactions;
  private final NotificationAcceptanceRepository notifications;
  private final NotificationTemplateCatalog templates;
  private final DatabaseTimePort databaseTime;
  private final BoundedTemplateRenderer renderer;
  private final DeliveryEscrowPort deliveryEscrow;

  public SubmitNotificationUseCase(
      NotificationIntentFactory intentFactory,
      FingerprintMaterialEncoder fingerprintEncoder,
      IntentFingerprintPort fingerprintPort,
      TransactionRunner transactions,
      NotificationAcceptanceRepository notifications,
      NotificationTemplateCatalog templates,
      DatabaseTimePort databaseTime,
      BoundedTemplateRenderer renderer,
      DeliveryEscrowPort deliveryEscrow) {
    this.intentFactory = intentFactory;
    this.fingerprintEncoder = fingerprintEncoder;
    this.fingerprintPort = fingerprintPort;
    this.transactions = transactions;
    this.notifications = notifications;
    this.templates = templates;
    this.databaseTime = databaseTime;
    this.renderer = renderer;
    this.deliveryEscrow = deliveryEscrow;
  }

  @Override
  public SubmitNotificationResult submit(SubmitNotificationCommand command) {
    CanonicalNotificationIntent intent = intentFactory.create(command);
    byte[] fingerprintMaterial = fingerprintEncoder.encode(intent);
    try {
      return transactions.required(() -> acceptInTransaction(intent, fingerprintMaterial));
    } catch (DuplicateNotificationRequestException concurrentDuplicate) {
      return transactions.required(() -> resolveExisting(intent, fingerprintMaterial));
    }
  }

  private SubmitNotificationResult acceptInTransaction(
      CanonicalNotificationIntent intent, byte[] fingerprintMaterial) {
    var existing =
        notifications.findByCallerAndRequestId(intent.callerService(), intent.requestId());
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), fingerprintMaterial);
    }

    FingerprintDigest fingerprint = fingerprintPort.compute(fingerprintMaterial);
    Instant acceptedAt = databaseTime.now();
    validateDeadline(intent, acceptedAt);
    NotificationTemplateVersion template =
        templates
            .findActive(intent.channel(), intent.semanticType(), intent.locale())
            .orElseThrow(
                () ->
                    new NotificationSubmissionException(
                        NotificationSubmissionError.TEMPLATE_NOT_ACTIVE,
                        "Notification template is not active"));
    RenderedNotification rendered = renderer.render(template, intent.semanticContent());
    UUID notificationId = UUID.randomUUID();
    EncryptedDeliveryPayload encrypted =
        deliveryEscrow.encrypt(notificationId, intent, template, rendered);
    Instant effectiveDeadline = acceptedAt.plus(intent.channel().deliveryDeadline());
    if (intent.messageNotAfter() != null && intent.messageNotAfter().isBefore(effectiveDeadline)) {
      effectiveDeadline = intent.messageNotAfter();
    }
    AcceptedNotificationWrite write =
        new AcceptedNotificationWrite(
            notificationId,
            intent,
            fingerprint,
            template,
            encrypted,
            NotificationLifecycle.ACCEPTED,
            acceptedAt,
            effectiveDeadline,
            acceptedAt.plus(MAX_SENSITIVE_RETENTION));
    notifications.insert(write);
    return new SubmitNotificationResult(
        notificationId, NotificationLifecycle.ACCEPTED, acceptedAt, false);
  }

  private SubmitNotificationResult resolveExisting(
      CanonicalNotificationIntent intent, byte[] fingerprintMaterial) {
    StoredAcceptedNotification existing =
        notifications
            .findByCallerAndRequestId(intent.callerService(), intent.requestId())
            .orElseThrow(
                () ->
                    new NotificationSubmissionException(
                        NotificationSubmissionError.NOTIFICATION_UNAVAILABLE,
                        "Notification idempotency resolution failed"));
    return replayOrConflict(existing, fingerprintMaterial);
  }

  private SubmitNotificationResult replayOrConflict(
      StoredAcceptedNotification existing, byte[] fingerprintMaterial) {
    if (!fingerprintPort.verify(
        fingerprintMaterial,
        existing.fingerprintVersion(),
        existing.fingerprintKeyId(),
        existing.fingerprint())) {
      throw new NotificationSubmissionException(
          NotificationSubmissionError.REQUEST_ID_CONFLICT,
          "Notification request identity was reused for different intent");
    }
    return new SubmitNotificationResult(
        existing.notificationId(), existing.lifecycle(), existing.acceptedAt(), true);
  }

  private static void validateDeadline(CanonicalNotificationIntent intent, Instant now) {
    if (intent.messageNotAfter() != null && !intent.messageNotAfter().isAfter(now)) {
      throw new NotificationSubmissionException(
          NotificationSubmissionError.INVALID_NOTIFICATION_REQUEST,
          "Notification delivery deadline has expired");
    }
  }
}
