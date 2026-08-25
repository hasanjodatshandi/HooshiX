package com.sajtech.identity.application.profile.model;

import java.time.Instant;
import java.util.UUID;

public record ProfileCommandRecord(
    UUID requestId,
    UUID userId,
    String operation,
    byte[] fingerprint,
    String fingerprintVersion,
    String fingerprintKeyId,
    String outcome,
    UUID resultId,
    Instant createdAt) {
  public ProfileCommandRecord {
    fingerprint = fingerprint.clone();
  }

  @Override
  public byte[] fingerprint() {
    return fingerprint.clone();
  }
}
