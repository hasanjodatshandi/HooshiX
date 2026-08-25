package com.sajtech.identity.application.password.port.in;

import com.sajtech.identity.application.mfa.model.MfaProof;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.util.UUID;

public record ConfirmPasswordRecoveryCommand(
    UUID requestId,
    RegistrationChannel channel,
    String contact,
    String code,
    String newPassword,
    byte[] clientAddress,
    MfaProof mfaProof) {
  public ConfirmPasswordRecoveryCommand {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  public ConfirmPasswordRecoveryCommand(
      UUID requestId,
      RegistrationChannel channel,
      String contact,
      String code,
      String newPassword,
      byte[] clientAddress) {
    this(requestId, channel, contact, code, newPassword, clientAddress, null);
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
