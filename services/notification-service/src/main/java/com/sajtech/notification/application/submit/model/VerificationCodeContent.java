package com.sajtech.notification.application.submit.model;

import com.sajtech.notification.domain.notification.model.NotificationSemanticType;

public record VerificationCodeContent(
    NotificationSemanticType semanticType, String code, int expiresMinutes)
    implements SemanticContent {
  public VerificationCodeContent {
    if (semanticType == null || !semanticType.isTimeBound()) {
      throw new IllegalArgumentException("Verification code requires a time-bound semantic type");
    }
    if (code == null || !code.matches("[0-9]{8}")) {
      throw new IllegalArgumentException("Verification code must contain exactly eight decimal digits");
    }
    if (expiresMinutes < 1 || expiresMinutes > 60) {
      throw new IllegalArgumentException("Verification expiry minutes must be between 1 and 60");
    }
  }
}
