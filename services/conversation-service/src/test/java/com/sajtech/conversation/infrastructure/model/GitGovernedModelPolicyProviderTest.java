package com.sajtech.conversation.infrastructure.model;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GitGovernedModelPolicyProviderTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void committedCandidateRemainsFailClosedEvenWhenRuntimeFlagIsTrue() {
    var provider = new GitGovernedModelPolicyProvider(true);

    assertThatThrownBy(provider::requireApprovedPolicy)
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(ConversationError.MODEL_EXECUTION_DISABLED));
  }

  @Test
  void exactApprovedStatelessTupleCanBeSelected() throws Exception {
    var provider = new GitGovernedModelPolicyProvider(true, JSON.readTree(approvedGovernance()));

    assertThat(provider.requireApprovedPolicy())
        .satisfies(
            policy -> {
              assertThat(policy.modelAlias()).isEqualTo("conversation-primary");
              assertThat(policy.promptVersion()).isEqualTo("1.0.0");
              assertThat(policy.priceVersion()).isEqualTo("2026-09-12");
              assertThat(policy.maximumReservationMicroUsd()).isEqualTo(70_000);
            });
  }

  @Test
  void toolOrStorageEnablementFailsClosed() throws Exception {
    String unsafe =
        approvedGovernance().replace("\"tools_enabled\":false", "\"tools_enabled\":true");
    var provider = new GitGovernedModelPolicyProvider(true, JSON.readTree(unsafe));

    assertThatThrownBy(provider::requireApprovedPolicy).isInstanceOf(ConversationException.class);
  }

  private static String approvedGovernance() {
    return """
        {
          "schema_version":1,
          "decision_status":"APPROVED_RUNTIME_ENABLED",
          "provider_data_controls":{"approval_status":"APPROVED"},
          "model_catalog":[{
            "logical_id":"conversation-primary",
            "lifecycle":"APPROVED",
            "execution_enabled":true,
            "store":false,
            "background":false,
            "tools_enabled":false,
            "prompt_version":"1.0.0",
            "price_id":"price",
            "price_version":"2026-09-12"
          }],
          "price_catalog":[{
            "price_id":"price",
            "price_version":"2026-09-12",
            "maximum_request_reservation_micro_usd":70000
          }]
        }
        """;
  }
}
