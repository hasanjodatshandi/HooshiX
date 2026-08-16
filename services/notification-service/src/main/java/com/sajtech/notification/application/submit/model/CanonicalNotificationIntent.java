package com.sajtech.notification.application.submit.model;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.time.Instant;
import java.util.UUID;

public record CanonicalNotificationIntent(
    UUID requestId,
    String callerService,
    NotificationChannel channel,
    String canonicalRecipient,
    String locale,
    NotificationSemanticType semanticType,
    SemanticContent semanticContent,
    Instant messageNotAfter) {}
