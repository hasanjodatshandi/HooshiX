package com.sajtech.notification.application.submit.model;

import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.time.Instant;
import java.util.UUID;

public record AcceptedNotificationWrite(
    UUID notificationId,
    CanonicalNotificationIntent intent,
    FingerprintDigest fingerprint,
    NotificationTemplateVersion template,
    EncryptedDeliveryPayload deliveryPayload,
    NotificationLifecycle lifecycle,
    Instant acceptedAt,
    Instant effectiveDeliveryDeadline,
    Instant sensitiveExpiresAt) {}
