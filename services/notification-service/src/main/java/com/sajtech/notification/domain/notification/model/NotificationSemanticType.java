package com.sajtech.notification.domain.notification.model;

public enum NotificationSemanticType {
  REGISTRATION_VERIFICATION_CODE(true),
  PASSWORD_RECOVERY_CODE(true),
  MFA_VERIFICATION_CODE(true),
  PASSWORD_CHANGED_NOTICE(false);

  private final boolean timeBound;

  NotificationSemanticType(boolean timeBound) {
    this.timeBound = timeBound;
  }

  public boolean isTimeBound() {
    return timeBound;
  }
}
