package com.sajtech.notification.application.submit.model;

import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.util.Map;

public record VerificationCodeContent(
    NotificationSemanticType semanticType, String code, int expiresMinutes)
    implements SemanticContent {
  public VerificationCodeContent {
    if (semanticType == null || !semanticType.isVerificationCode()) {
      throw new IllegalArgumentException("Verification code semantic type is required");
    }
    if (code == null || code.isBlank() || expiresMinutes <= 0) {
      throw new IllegalArgumentException("Verification code content is invalid");
    }
  }

  @Override
  public Map<String, String> templateVariables() {
    return Map.of("code", code, "expires_minutes", Integer.toString(expiresMinutes));
  }
}
