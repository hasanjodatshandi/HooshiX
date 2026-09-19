package com.sajtech.conversation.infrastructure.model;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.port.out.ModelPolicyProvider;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class GitGovernedModelPolicyProvider implements ModelPolicyProvider {
  static final String GOVERNANCE_RESOURCE = "mlops/governance/v1/governance.json";
  static final String PROMPT_RESOURCE = "mlops/prompts/conversation-system-v1.txt";
  private static final String EXPECTED_DECISION_STATUS = "APPROVED_RUNTIME_ENABLED";
  private static final String EXPECTED_LIFECYCLE = "APPROVED_100";
  private static final String EXPECTED_DATA_CONTROL = "APPROVED";
  private final boolean runtimeEnabled;
  private final ModelExecutionPolicy approvedPolicy;

  public GitGovernedModelPolicyProvider(boolean runtimeEnabled) {
    this(runtimeEnabled, loadJson(GOVERNANCE_RESOURCE), loadBytes(PROMPT_RESOURCE));
  }

  GitGovernedModelPolicyProvider(boolean runtimeEnabled, JsonNode governance, byte[] prompt) {
    this.runtimeEnabled = runtimeEnabled;
    this.approvedPolicy = approvedPolicy(governance, prompt);
  }

  @Override
  public ModelExecutionPolicy requireApprovedPolicy() {
    if (!runtimeEnabled || approvedPolicy == null) {
      throw new ConversationException(
          ConversationError.MODEL_EXECUTION_DISABLED, "Model execution is disabled");
    }
    return approvedPolicy;
  }

  private static ModelExecutionPolicy approvedPolicy(JsonNode root, byte[] prompt) {
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
      if (!bool(model, "execution_enabled")
          || !EXPECTED_LIFECYCLE.equals(text(model, "lifecycle"))
          || !"openai".equals(text(model, "provider"))
          || !"responses".equals(text(model, "endpoint"))
          || bool(model, "store")
          || bool(model, "background")
          || bool(model, "tools_enabled")) {
        return null;
      }
      String promptId = text(model, "prompt_id");
      String promptVersion = text(model, "prompt_version");
      if (!promptMatches(root.path("prompt_catalog"), promptId, promptVersion, prompt)) return null;
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
          text(model, "logical_id"), promptVersion, priceVersion, reservation);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static boolean promptMatches(
      JsonNode prompts, String promptId, String promptVersion, byte[] prompt) {
    if (!prompts.isArray() || prompt == null || prompt.length == 0) return false;
    String digest = sha256(prompt);
    for (JsonNode candidate : prompts) {
      if (promptId.equals(text(candidate, "prompt_id"))
          && promptVersion.equals(text(candidate, "prompt_version"))
          && EXPECTED_LIFECYCLE.equals(text(candidate, "status"))
          && PROMPT_RESOURCE.equals(text(candidate, "path"))
          && digest.equals(text(candidate, "sha256"))) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode loadJson(String resource) {
    try {
      return new ObjectMapper().readTree(loadBytes(resource));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Model governance resource is invalid", exception);
    }
  }

  private static byte[] loadBytes(String resource) {
    try (InputStream input =
        GitGovernedModelPolicyProvider.class.getClassLoader().getResourceAsStream(resource)) {
      if (input == null) throw new IOException("missing resource");
      return input.readAllBytes();
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Model governance resource is invalid", exception);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = Objects.requireNonNull(node).get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException("Model governance field is invalid");
    }
    return value.textValue();
  }

  private static boolean bool(JsonNode node, String field) {
    JsonNode value = Objects.requireNonNull(node).get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException("Model governance field is invalid");
    }
    return value.booleanValue();
  }
}
