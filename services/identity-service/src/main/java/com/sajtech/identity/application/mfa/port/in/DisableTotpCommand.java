package com.sajtech.identity.application.mfa.port.in;

import com.sajtech.identity.application.mfa.model.MfaProof;
import java.util.UUID;

public record DisableTotpCommand(
    UUID requestId, String refreshCredential, MfaProof proof, byte[] clientAddress) {
  public DisableTotpCommand {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
