package com.sajtech.notification.application.submit;

public final class NotificationSubmissionException extends RuntimeException {
  private final NotificationSubmissionError error;

  public NotificationSubmissionException(NotificationSubmissionError error, String message) {
    super(message);
    this.error = error;
  }

  public NotificationSubmissionException(
      NotificationSubmissionError error, String message, Throwable cause) {
    super(message, cause);
    this.error = error;
  }

  public NotificationSubmissionError error() {
    return error;
  }
}
