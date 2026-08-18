package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.util.UUID;

public record ConfirmRegistrationCommand(
    UUID requestId,
    RegistrationChannel channel,
    String contact,
    String code,
    byte[] clientAddress) {
  public ConfirmRegistrationCommand {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
