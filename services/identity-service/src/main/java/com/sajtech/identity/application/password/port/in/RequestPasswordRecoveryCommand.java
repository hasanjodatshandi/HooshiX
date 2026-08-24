package com.sajtech.identity.application.password.port.in;

import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.util.UUID;

public record RequestPasswordRecoveryCommand(
    UUID requestId, RegistrationChannel channel, String contact, byte[] clientAddress) {
  public RequestPasswordRecoveryCommand {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
