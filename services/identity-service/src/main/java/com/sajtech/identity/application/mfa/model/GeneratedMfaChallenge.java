package com.sajtech.identity.application.mfa.model;

public record GeneratedMfaChallenge(String encoded, MfaDigest digest) {
  public GeneratedMfaChallenge {
    if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{43}") || digest == null) {
      throw new IllegalArgumentException("Generated MFA challenge is invalid");
    }
  }
}
