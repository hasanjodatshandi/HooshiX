package com.sajtech.identity.application.mfa.model;

public record GeneratedRecoveryCode(String encoded, MfaDigest digest) {
  public GeneratedRecoveryCode {
    if (encoded == null || !encoded.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}") || digest == null) {
      throw new IllegalArgumentException("Generated recovery code is invalid");
    }
  }
}
