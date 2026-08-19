package com.sajtech.identity.application.authentication.model;

public record RefreshDigest(String keyId, String version, byte[] digest) {
  public RefreshDigest {
    digest = digest.clone();
  }

  @Override
  public byte[] digest() {
    return digest.clone();
  }
}
