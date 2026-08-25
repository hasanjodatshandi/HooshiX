package com.sajtech.identity.application.mfa.model;

public record MfaDigest(byte[] digest, String keyId, String version) {
  public MfaDigest {
    if (digest == null
        || digest.length != 32
        || keyId == null
        || keyId.isBlank()
        || version == null
        || version.isBlank()) {
      throw new IllegalArgumentException("MFA digest is invalid");
    }
    digest = digest.clone();
  }

  @Override
  public byte[] digest() {
    return digest.clone();
  }
}
