package com.sajtech.conversation.infrastructure.model;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
    var provider =
        new GitGovernedModelPolicyProvider(
            true, JSON.readTree(approvedGovernance()), "prompt".getBytes(StandardCharsets.UTF_8));

    assertThat(provider.requireApprovedPolicy())
        .satisfies(
            policy -> {
              assertThat(policy.modelAlias()).isEqualTo("conversation-primary");
              assertThat(policy.providerModelId()).isEqualTo("gpt-5.4-2026-03-05");
              assertThat(policy.promptVersion()).isEqualTo("2.0.0");
              assertThat(policy.priceVersion()).isEqualTo("2026-09-12");
              assertThat(policy.maximumInputTokens()).isEqualTo(16_000);
              assertThat(policy.maximumOutputTokens()).isEqualTo(2_000);
              assertThat(policy.actualCostMicroUsd(1_000, 100, 100)).isEqualTo(3_775);
              assertThat(policy.maximumReservationMicroUsd()).isEqualTo(70_000);
            });
    assertThat(provider.requireApprovedPolicy(UUID.randomUUID())).isNotNull();
  }

  @Test
  void rejectsCanaryPercentThatDoesNotMatchRuntimeStateMachine() throws Exception {
    var governance = JSON.readTree(approvedGovernance());
    byte[] prompt = "prompt".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> new GitGovernedModelPolicyProvider(false, 1, governance, prompt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GitGovernedModelPolicyProvider(true, 0, governance, prompt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GitGovernedModelPolicyProvider(true, 2, governance, prompt))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canaryLifecycleMustMatchPercentageAndTenantCohortIsStable() throws Exception {
    String canaryGovernance = approvedGovernance().replace("APPROVED_100", "CANARY_1");
    var provider =
        new GitGovernedModelPolicyProvider(
            true, 1, JSON.readTree(canaryGovernance), "prompt".getBytes(StandardCharsets.UTF_8));
    UUID included = null;
    UUID excluded = null;
    for (int index = 0; index < 10_000 && (included == null || excluded == null); index++) {
      UUID candidate = UUID.nameUUIDFromBytes(("tenant-" + index).getBytes(StandardCharsets.UTF_8));
      try {
        provider.requireApprovedPolicy(candidate);
        included = candidate;
      } catch (ConversationException exception) {
        excluded = candidate;
      }
    }

    assertThat(included).isNotNull();
    assertThat(excluded).isNotNull();
    assertThat(provider.requireApprovedPolicy(included)).isNotNull();
    UUID rejected = excluded;
    assertThatThrownBy(() -> provider.requireApprovedPolicy(rejected))
        .isInstanceOf(ConversationException.class);
    var mismatch =
        new GitGovernedModelPolicyProvider(
            true, 5, JSON.readTree(canaryGovernance), "prompt".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(mismatch::requireApprovedPolicy).isInstanceOf(ConversationException.class);
  }

  @Test
  void toolOrStorageEnablementFailsClosed() throws Exception {
    String unsafe =
        approvedGovernance().replace("\"tools_enabled\":false", "\"tools_enabled\":true");
    var provider =
        new GitGovernedModelPolicyProvider(
            true, JSON.readTree(unsafe), "prompt".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(provider::requireApprovedPolicy).isInstanceOf(ConversationException.class);
  }

  @Test
  void promptDigestMismatchFailsClosed() throws Exception {
    var provider =
        new GitGovernedModelPolicyProvider(
            true, JSON.readTree(approvedGovernance()), "tampered".getBytes(StandardCharsets.UTF_8));

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
            "lifecycle":"APPROVED_100",
            "execution_enabled":true,
            "provider":"openai",
            "provider_model_id":"gpt-5.4-2026-03-05",
            "endpoint":"responses",
            "reasoning_effort":"none",
            "store":false,
            "background":false,
            "tools_enabled":false,
            "max_input_tokens":16000,
            "max_output_tokens":2000,
            "prompt_id":"conversation-system",
            "prompt_version":"2.0.0",
            "price_id":"price",
            "price_version":"2026-09-12"
          }],
          "prompt_catalog":[{
            "prompt_id":"conversation-system",
            "prompt_version":"2.0.0",
            "status":"APPROVED_100",
            "path":"mlops/prompts/conversation-system-v2.txt",
            "sha256":"cf07194ee232eb531e15f690000d19846dea69cf05504782658afcfacb9228a2"
          }],
          "price_catalog":[{
            "price_id":"price",
            "price_version":"2026-09-12",
            "currency":"USD",
            "unit":"MICRO_USD_PER_MILLION_TOKENS",
            "input":2500000,
            "cached_input":250000,
            "output":15000000,
            "maximum_request_reservation_micro_usd":70000
          }]
        }
        """;
  }
}
