package com.sajtech.identity.application.authentication.model;

import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.util.UUID;

public record AuthenticateLocalCommand(
    UUID requestId,
    RegistrationChannel channel,
    String contact,
    String password,
    byte[] clientAddress) {
  public AuthenticateLocalCommand {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
