package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.util.UUID;

public record ResendRegistrationCommand(
    UUID requestId, RegistrationChannel channel, String contact, byte[] clientAddress) {
  public ResendRegistrationCommand {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
