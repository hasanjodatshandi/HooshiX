package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.util.UUID;

public record RegisterLocalCommand(
    UUID requestId,
    RegistrationChannel channel,
    String contact,
    String password,
    RegistrationLocale locale,
    String firstName,
    String lastName,
    String fatherName,
    byte[] clientAddress) {
  public RegisterLocalCommand {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
