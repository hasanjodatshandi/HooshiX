package com.sajtech.conversation.infrastructure.model;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.port.out.ModelPolicyProvider;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class GitGovernedModelPolicyProvider implements ModelPolicyProvider {
  static final String GOVERNANCE_RESOURCE = "mlops/governance/v1/governance.json";
  private static final String EXPECTED_DECISION_STATUS = "APPROVED_RUNTIME_ENABLED";
  private static final String EXPECTED_LIFECYCLE = "APPROVED";
  private static final String EXPECTED_DATA_CONTROL = "APPROVED";
  private final boolean runtimeEnabled;
  private final ModelExecutionPolicy approvedPolicy;

  public GitGovernedModelPolicyProvider(boolean runtimeEnabled) {
    this(runtimeEnabled, load(GOVERNANCE_RESOURCE));
  }

  GitGovernedModelPolicyProvider(boolean runtimeEnabled, JsonNode governance) {
    this.runtimeEnabled = runtimeEnabled;
    this.approvedPolicy = approvedPolicy(governance);
  }

  @Override
  public ModelExecutionPolicy requireApprovedPolicy() {
    if (!runtimeEnabled || approvedPolicy == null) {
      throw new ConversationException(
          ConversationError.MODEL_EXECUTION_DISABLED, "Model execution is disabled");
    }
    return approvedPolicy;
  }

  private static ModelExecutionPolicy approvedPolicy(JsonNode root) {
    try {
      if (!root.path("schema_version").isInt()
          || root.path("schema_version").intValue() != 1
          || !EXPECTED_DECISION_STATUS.equals(text(root, "decision_status"))
          || !EXPECTED_DATA_CONTROL.equals(
              text(root.path("provider_data_controls"), "approval_status"))) {
        return null;
      }
      JsonNode models = root.path("model_catalog");
      JsonNode prices = root.path("price_catalog");
      if (!models.isArray() || models.size() != 1 || !prices.isArray()) return null;
      JsonNode model = models.get(0);
      if (!model.path("execution_enabled").asBoolean()
          || !EXPECTED_LIFECYCLE.equals(text(model, "lifecycle"))
          || model.path("store").asBoolean()
          || model.path("background").asBoolean()
          || model.path("tools_enabled").asBoolean()) {
        return null;
      }
      String priceId = text(model, "price_id");
      String priceVersion = text(model, "price_version");
      JsonNode price = null;
      for (JsonNode candidate : prices) {
        if (priceId.equals(text(candidate, "price_id"))
            && priceVersion.equals(text(candidate, "price_version"))) {
          price = candidate;
        }
      }
      if (price == null) return null;
      JsonNode reservationNode = price.path("maximum_request_reservation_micro_usd");
      if (!reservationNode.isIntegralNumber()) return null;
      long reservation = reservationNode.longValue();
      return new ModelExecutionPolicy(
          text(model, "logical_id"), text(model, "prompt_version"), priceVersion, reservation);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static JsonNode load(String resource) {
    try (InputStream input =
        GitGovernedModelPolicyProvider.class.getClassLoader().getResourceAsStream(resource)) {
      if (input == null) throw new IOException("missing resource");
      return new ObjectMapper().readTree(input);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Model governance resource is invalid", exception);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = Objects.requireNonNull(node).get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("Model governance field is invalid");
    }
    return value.asText();
  }
}
