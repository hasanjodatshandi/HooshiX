package com.sajtech.identity.application.mfa.port.in;

import com.sajtech.identity.application.mfa.model.MfaProof;
import java.util.UUID;

public record StartTotpEnrollmentCommand(
    UUID requestId, String refreshCredential, byte[] clientAddress, MfaProof currentProof) {
  public StartTotpEnrollmentCommand {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
