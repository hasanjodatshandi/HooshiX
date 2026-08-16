package com.sajtech.notification.application.delivery.model;

public enum ProviderAttemptAction {
  MARK_PROVIDER_ACCEPTED,
  RETRY_AFTER,
  RECONCILE,
  FAIL_PERMANENT,
  EXPIRE
}
