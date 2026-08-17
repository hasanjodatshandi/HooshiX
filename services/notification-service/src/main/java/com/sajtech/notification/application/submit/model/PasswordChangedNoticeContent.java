package com.sajtech.notification.application.submit.model;

import com.sajtech.notification.domain.notification.model.NotificationSemanticType;

public record PasswordChangedNoticeContent() implements SemanticContent {
  @Override
  public NotificationSemanticType semanticType() {
    return NotificationSemanticType.PASSWORD_CHANGED_NOTICE;
  }
}
