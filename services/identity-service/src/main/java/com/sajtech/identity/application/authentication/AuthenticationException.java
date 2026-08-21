package com.sajtech.identity.application.authentication;

public final class AuthenticationException extends RuntimeException {
  private final AuthenticationError error;

  public AuthenticationException(AuthenticationError error, String safeMessage) {
    super(safeMessage);
    this.error = error;
  }

  public AuthenticationException(AuthenticationError error, String safeMessage, Throwable cause) {
    super(safeMessage, cause);
    this.error = error;
  }

  public AuthenticationError error() {
    return error;
  }
}
