package com.sajtech.identity.application.registration.service;

import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.model.CommandDedupRecord;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;

public final class IdempotencyGuard {
  private final IntentFingerprintPort fingerprints;

  public IdempotencyGuard(IntentFingerprintPort fingerprints) {
    this.fingerprints = fingerprints;
  }

  public String requireEqual(byte[] material, String operation, CommandDedupRecord stored) {
    if (!operation.equals(stored.operation()) || !fingerprints.matches(material, stored)) {
      throw new RegistrationException(
          RegistrationError.REQUEST_ID_CONFLICT, "Request ID was already used for another intent");
    }
    return stored.outcome();
  }
}
