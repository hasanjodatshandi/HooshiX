package com.sajtech.identity.application.registration.model;

import java.time.Instant;
import java.util.UUID;

public record CommandDedupRecord(
    UUID requestId,
    String operation,
    byte[] fingerprint,
    String fingerprintVersion,
    String fingerprintKeyId,
    String outcome,
    Instant createdAt) {
  public CommandDedupRecord {
    fingerprint = fingerprint.clone();
  }

  @Override
  public byte[] fingerprint() {
    return fingerprint.clone();
  }
}
