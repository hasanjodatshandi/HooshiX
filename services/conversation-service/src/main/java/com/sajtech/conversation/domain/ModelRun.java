package com.sajtech.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ModelRun(
    UUID id,
    UUID conversationId,
    ModelRunState state,
    String modelAlias,
    String promptVersion,
    String priceVersion,
    long reservedCostMicroUsd,
    long chargedCostMicroUsd,
    ModelRunFailure failure,
    boolean cancellationRequested,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt) {
  public ModelRun {
    Objects.requireNonNull(id);
    Objects.requireNonNull(conversationId);
    Objects.requireNonNull(state);
    Objects.requireNonNull(modelAlias);
    Objects.requireNonNull(promptVersion);
    Objects.requireNonNull(priceVersion);
    Objects.requireNonNull(failure);
    Objects.requireNonNull(createdAt);
    if (reservedCostMicroUsd < 0
        || chargedCostMicroUsd < 0
        || chargedCostMicroUsd > reservedCostMicroUsd) {
      throw new IllegalArgumentException("Model run cost is invalid");
    }
    boolean terminal =
        switch (state) {
          case SUCCEEDED, FAILED, CANCELED, OUTCOME_UNKNOWN -> true;
          case QUEUED, RUNNING -> false;
        };
    if (terminal != (completedAt != null) || (state == ModelRunState.QUEUED && startedAt != null)) {
      throw new IllegalArgumentException("Model run timestamps are invalid");
    }
  }
}
