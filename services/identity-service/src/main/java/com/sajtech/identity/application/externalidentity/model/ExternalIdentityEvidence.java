package com.sajtech.identity.application.externalidentity.model;

import java.time.Instant;

public record ExternalIdentityEvidence(
    byte[] evidenceId,
    Instant issuedAt,
    String issuer,
    String subject,
    int metadataVersion,
    String email,
    boolean emailVerified,
    String givenName,
    String familyName) {
  public ExternalIdentityEvidence {
    evidenceId = evidenceId == null ? null : evidenceId.clone();
  }

  @Override
  public byte[] evidenceId() {
    return evidenceId == null ? null : evidenceId.clone();
  }
}
