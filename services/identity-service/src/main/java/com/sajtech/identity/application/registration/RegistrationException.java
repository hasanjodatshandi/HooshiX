package com.sajtech.identity.application.registration;

import java.util.Objects;

public final class RegistrationException extends RuntimeException {
  private final RegistrationError error;

  public RegistrationException(RegistrationError error) {
    super(error.name());
    this.error = Objects.requireNonNull(error, "error");
  }

  public RegistrationException(RegistrationError error, Throwable cause) {
    super(error.name(), cause);
    this.error = Objects.requireNonNull(error, "error");
  }

  public RegistrationError error() {
    return error;
  }
}
