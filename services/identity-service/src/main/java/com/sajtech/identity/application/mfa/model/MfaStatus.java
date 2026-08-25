package com.sajtech.identity.application.mfa.model;

public record MfaStatus(boolean totpEnabled, int recoveryCodesRemaining) {
  public MfaStatus {
    if (recoveryCodesRemaining < 0 || recoveryCodesRemaining > 10) {
      throw new IllegalArgumentException("MFA status is invalid");
    }
  }
}
