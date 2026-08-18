package com.sajtech.identity.application.notificationhandoff;

public final class NotificationHandoffException extends RuntimeException {
  private final boolean retryable;
  private final String machineCode;

  public NotificationHandoffException(boolean retryable, String machineCode) {
    super(machineCode);
    this.retryable = retryable;
    this.machineCode = machineCode;
  }

  public NotificationHandoffException(boolean retryable, String machineCode, Throwable cause) {
    super(machineCode, cause);
    this.retryable = retryable;
    this.machineCode = machineCode;
  }

  public boolean retryable() {
    return retryable;
  }

  public String machineCode() {
    return machineCode;
  }
}
