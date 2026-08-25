package com.sajtech.identity.application.password.model;

public record GeneratedRecoveryProof(String code, byte[] verifier, String keyId) {
  public GeneratedRecoveryProof {
    verifier = verifier.clone();
  }

  @Override
  public byte[] verifier() {
    return verifier.clone();
  }
}
