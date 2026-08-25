package com.sajtech.identity.application.mfa.port.in;

import java.util.UUID;

public record ConfirmTotpEnrollmentCommand(
    UUID requestId,
    String refreshCredential,
    String enrollmentChallenge,
    String totpCode,
    byte[] clientAddress) {
  public ConfirmTotpEnrollmentCommand {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
