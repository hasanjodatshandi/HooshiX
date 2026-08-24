package com.sajtech.identity.application.password;

public final class PasswordException extends RuntimeException {
  private final PasswordError error;

  public PasswordException(PasswordError error, String message) {
    super(message);
    this.error = error;
  }

  public PasswordException(PasswordError error, String message, Throwable cause) {
    super(message, cause);
    this.error = error;
  }

  public PasswordError error() {
    return error;
  }
}
