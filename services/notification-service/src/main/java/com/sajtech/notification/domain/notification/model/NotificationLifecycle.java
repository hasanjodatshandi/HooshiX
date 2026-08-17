package com.sajtech.notification.domain.notification.model;

public enum NotificationLifecycle {
  ACCEPTED(false),
  DISPATCHING(false),
  RETRY_WAIT(false),
  PROVIDER_ACCEPTED(false),
  DELIVERED(true),
  FAILED_PERMANENT(true),
  EXPIRED(true),
  DELIVERY_STATUS_UNKNOWN(true);

  private final boolean terminal;

  NotificationLifecycle(boolean terminal) {
    this.terminal = terminal;
  }

  public boolean isTerminal() {
    return terminal;
  }
}
