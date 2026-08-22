package com.sajtech.notification.application.delivery.model;

public record DeliveryBatchResult(int claimed, int completed) {
  public DeliveryBatchResult {
    if (claimed < 0 || completed < 0 || completed > claimed) {
      throw new IllegalArgumentException("Invalid delivery batch result");
    }
  }
}
