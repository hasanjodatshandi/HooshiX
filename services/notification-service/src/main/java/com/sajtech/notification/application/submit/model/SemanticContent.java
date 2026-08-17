package com.sajtech.notification.application.submit.model;

import com.sajtech.notification.domain.notification.model.NotificationSemanticType;

public sealed interface SemanticContent
    permits VerificationCodeContent, PasswordChangedNoticeContent {
  NotificationSemanticType semanticType();
}
