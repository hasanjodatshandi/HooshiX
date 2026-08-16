package com.sajtech.compromisedpassword.application.lookup;

public final class LookupUnavailableException extends RuntimeException {
  public LookupUnavailableException(String message) {
    super(message);
  }

  public LookupUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
