package com.sajtech.identity.application.externalidentity;

public final class ExternalIdentityException extends RuntimeException {
  private final ExternalIdentityError error;

  public ExternalIdentityException(ExternalIdentityError error, String message) {
    super(message);
    this.error = error;
  }

  public ExternalIdentityError error() {
    return error;
  }
}
