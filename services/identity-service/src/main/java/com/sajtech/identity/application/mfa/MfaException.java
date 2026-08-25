package com.sajtech.identity.application.mfa;

public final class MfaException extends RuntimeException {
  private final MfaError error;

  public MfaException(MfaError error, String message) {
    super(message);
    this.error = error;
  }

  public MfaError error() {
    return error;
  }
}
