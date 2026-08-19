package com.sajtech.identity.application.registration;

public final class RegistrationException extends RuntimeException {
  private final RegistrationError error;

  public RegistrationException(RegistrationError error, String safeMessage) {
    super(safeMessage);
    this.error = error;
  }

  public RegistrationException(RegistrationError error, String safeMessage, Throwable cause) {
    super(safeMessage, cause);
    this.error = error;
  }

  public RegistrationError error() {
    return error;
  }
}
