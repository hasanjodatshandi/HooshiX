package com.sajtech.notification.application.delivery.model;

public record ReconciliationBatchResult(int recoveredStale, int claimed, int processed) {
  public ReconciliationBatchResult {
    if (recoveredStale < 0 || claimed < 0 || processed < 0 || processed > claimed) {
      throw new IllegalArgumentException("Invalid reconciliation batch result");
    }
  }
}
