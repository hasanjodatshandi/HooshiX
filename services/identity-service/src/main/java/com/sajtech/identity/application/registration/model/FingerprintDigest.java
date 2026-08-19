package com.sajtech.identity.application.registration.model;

public record FingerprintDigest(byte[] value, String version, String keyId) {
  public FingerprintDigest {
    value = value.clone();
  }

  @Override
  public byte[] value() {
    return value.clone();
  }
}
