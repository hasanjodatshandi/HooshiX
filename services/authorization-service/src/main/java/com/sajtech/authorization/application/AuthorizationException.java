package com.sajtech.authorization.application;

public final class AuthorizationException extends RuntimeException {
  private final AuthorizationError error;
  public AuthorizationException(AuthorizationError error, String message) { super(message); this.error = error; }
  public AuthorizationException(AuthorizationError error, String message, Throwable cause) { super(message, cause); this.error = error; }
  public AuthorizationError error() { return error; }
}
