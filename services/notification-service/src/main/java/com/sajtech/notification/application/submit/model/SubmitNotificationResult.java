package com.sajtech.notification.application.submit.model;

import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.time.Instant;
import java.util.UUID;

public record SubmitNotificationResult(
    UUID notificationId, NotificationLifecycle lifecycle, Instant acceptedAt, boolean replay) {}
