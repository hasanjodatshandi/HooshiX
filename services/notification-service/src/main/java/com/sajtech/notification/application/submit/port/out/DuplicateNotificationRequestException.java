package com.sajtech.notification.application.submit.port.out;

public final class DuplicateNotificationRequestException extends RuntimeException {
  public DuplicateNotificationRequestException(Throwable cause) {
    super("Notification request identity already exists", cause);
  }
}
