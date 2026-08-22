package com.sajtech.notification.application.template.model;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.util.UUID;

public record NotificationTemplateVersion(
    UUID versionId,
    NotificationChannel channel,
    NotificationSemanticType semanticType,
    String locale,
    String contentSha256,
    String subjectTemplate,
    String textTemplate,
    String htmlTemplate) {
  public NotificationTemplateVersion {
    if (versionId == null
        || channel == null
        || semanticType == null
        || locale == null
        || contentSha256 == null
        || textTemplate == null) {
      throw new IllegalArgumentException("Template version is incomplete");
    }
    if (!contentSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Template digest must be canonical SHA-256 hex");
    }
    if (!semanticType.supportsChannel(channel)) {
      throw new IllegalArgumentException("Template semantic type is not supported for its channel");
    }
    if (textTemplate.isBlank()) {
      throw new IllegalArgumentException("Template text body is required");
    }
    if (channel == NotificationChannel.EMAIL
        && (subjectTemplate == null
            || subjectTemplate.isBlank()
            || htmlTemplate == null
            || htmlTemplate.isBlank())) {
      throw new IllegalArgumentException("Email template requires subject, text and HTML bodies");
    }
    if (channel == NotificationChannel.SMS && (subjectTemplate != null || htmlTemplate != null)) {
      throw new IllegalArgumentException("SMS template must contain only a text body");
    }
  }
}
