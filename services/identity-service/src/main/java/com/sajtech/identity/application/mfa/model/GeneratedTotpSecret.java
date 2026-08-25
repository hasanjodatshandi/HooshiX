package com.sajtech.identity.application.mfa.model;

public record GeneratedTotpSecret(String base32, String otpauthUri, EncryptedTotpSecret encrypted) {
  public GeneratedTotpSecret {
    if (base32 == null
        || !base32.matches("[A-Z2-7]{52}")
        || otpauthUri == null
        || !otpauthUri.startsWith("otpauth://totp/")
        || encrypted == null) {
      throw new IllegalArgumentException("Generated TOTP secret is invalid");
    }
  }
}
