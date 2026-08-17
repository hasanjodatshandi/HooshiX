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
  }
}
