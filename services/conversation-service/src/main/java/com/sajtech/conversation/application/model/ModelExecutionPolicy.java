package com.sajtech.conversation.application.model;

import java.util.Objects;

public record ModelExecutionPolicy(
    String modelAlias, String promptVersion, String priceVersion, long maximumReservationMicroUsd) {
  public ModelExecutionPolicy {
    Objects.requireNonNull(modelAlias);
    Objects.requireNonNull(promptVersion);
    Objects.requireNonNull(priceVersion);
    if (maximumReservationMicroUsd <= 0) {
      throw new IllegalArgumentException("Model execution policy reservation is invalid");
    }
  }
}
