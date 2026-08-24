package com.sajtech.identity.application.notification.usecase;

import com.sajtech.identity.application.notification.model.*;
import com.sajtech.identity.application.notification.port.in.ReportNotificationResult;
import com.sajtech.identity.application.notification.port.out.NotificationResultStore;

public final class ReportNotificationResultUseCase implements ReportNotificationResult {
  private final NotificationResultStore store;

  public ReportNotificationResultUseCase(NotificationResultStore store) {
    this.store = store;
  }

  @Override
  public NotificationResultApplyOutcome report(NotificationTerminalResult result) {
    if (result == null)
      throw new IllegalArgumentException("Notification terminal result is required");
    return store.apply(result);
  }
}
