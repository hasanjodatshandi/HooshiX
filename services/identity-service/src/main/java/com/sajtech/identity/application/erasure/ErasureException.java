package com.sajtech.identity.application.erasure;

public final class ErasureException extends RuntimeException {
  private final ErasureError error;

  public ErasureException(ErasureError error, String message) {
    super(message);
    this.error = error;
  }

  public ErasureError error() {
    return error;
  }
}
