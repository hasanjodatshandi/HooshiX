package com.sajtech.webbff.application;

public final class BffException extends RuntimeException {
  private final BffError error;

  public BffException(BffError error, String message) {
    super(message);
    this.error = error;
  }

  public BffException(BffError error, String message, Throwable cause) {
    super(message, cause);
    this.error = error;
  }

  public BffError error() {
    return error;
  }
}
