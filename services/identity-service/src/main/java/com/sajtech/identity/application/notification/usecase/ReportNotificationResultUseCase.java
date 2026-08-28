package com.sajtech.identity.application.notification.usecase;

import com.sajtech.identity.application.notification.model.*;
import com.sajtech.identity.application.notification.port.in.ReportNotificationResult;
import com.sajtech.identity.application.notification.port.out.NotificationResultStore;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;

public final class ReportNotificationResultUseCase implements ReportNotificationResult {
  private final NotificationResultStore store;
  private final TransactionRunner transactions;

  public ReportNotificationResultUseCase(
      NotificationResultStore store, TransactionRunner transactions) {
    this.store = store;
    this.transactions = transactions;
  }

  @Override
  public NotificationResultApplyOutcome report(NotificationTerminalResult result) {
    if (result == null)
      throw new IllegalArgumentException("Notification terminal result is required");
    return transactions.required(() -> store.apply(result));
  }
}
