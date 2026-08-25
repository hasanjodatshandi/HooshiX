package com.sajtech.identity.application.mfa.model;

public record MfaProof(MfaProofType type, String code) {
  public MfaProof {
    if (type == null || code == null) throw new IllegalArgumentException("MFA proof is required");
  }
}
