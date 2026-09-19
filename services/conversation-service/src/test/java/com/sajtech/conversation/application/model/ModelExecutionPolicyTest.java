package com.sajtech.conversation.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelExecutionPolicyTest {
  @Test
  void reconcilesIntegerMicroUsdWithOneFinalRoundUp() {
    ModelExecutionPolicy policy = policy();

    assertThat(policy.actualCostMicroUsd(1_000, 100, 100)).isEqualTo(3_775);
    assertThat(policy.actualCostMicroUsd(1, 0, 0)).isEqualTo(3);
    assertThat(policy.actualCostMicroUsd(16_000, 0, 2_000)).isEqualTo(70_000);
  }

  @Test
  void rejectsUsageOutsideApprovedBoundsAndInconsistentReservation() {
    assertThatThrownBy(() -> policy().actualCostMicroUsd(17_000, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy().actualCostMicroUsd(100, 101, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ModelExecutionPolicy(
                    "conversation-primary",
                    "gpt-5.4-2026-03-05",
                    "1.0.0",
                    "2026-09-12",
                    16_000,
                    2_000,
                    2_500_000,
                    250_000,
                    15_000_000,
                    69_999))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static ModelExecutionPolicy policy() {
    return new ModelExecutionPolicy(
        "conversation-primary",
        "gpt-5.4-2026-03-05",
        "1.0.0",
        "2026-09-12",
        16_000,
        2_000,
        2_500_000,
        250_000,
        15_000_000,
        70_000);
  }
}
