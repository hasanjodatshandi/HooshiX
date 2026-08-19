package com.sajtech.identity.application.registration.model;

public record GeneratedChallenge(String code, byte[] verifier, String keyId) {
  public GeneratedChallenge {
    verifier = verifier.clone();
  }

  @Override
  public byte[] verifier() {
    return verifier.clone();
  }
}
