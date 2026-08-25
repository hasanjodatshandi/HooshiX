package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiRegistrationContractTest {
  @Test
  void canonicalContractDefinesReviewedIdentityRoutesAndRfc9457ProblemShape() throws Exception {
    Map<String, Object> document;
    try (InputStream input = Files.newInputStream(Path.of("contracts/openapi.yaml"))) {
      document = new Yaml().load(input);
    }

    assertThat(document.get("openapi")).isEqualTo("3.1.0");
    Map<String, Object> paths = map(document.get("paths"));
    assertThat(paths.keySet())
        .containsExactly(
            "/api/v1/identity/registration",
            "/api/v1/identity/registration/resend",
            "/api/v1/identity/registration/confirm",
            "/api/v1/identity/profile",
            "/api/v1/identity/contacts",
            "/api/v1/identity/contacts/{id}/resend",
            "/api/v1/identity/contacts/{id}/verify",
            "/api/v1/identity/contacts/{id}/primary",
            "/api/v1/identity/contacts/{id}",
            "/api/v1/password/change",
            "/api/v1/password/recovery/request",
            "/api/v1/password/recovery/confirm");
    assertThat(responses(paths, "/api/v1/identity/registration"))
        .containsKeys("202", "400", "403", "409", "429", "503");
    assertThat(responses(paths, "/api/v1/identity/registration/resend")).containsKey("202");
    assertThat(responses(paths, "/api/v1/identity/registration/confirm")).containsKey("200");
    assertThat(responses(paths, "/api/v1/identity/contacts/{id}/verify")).containsKey("200");
    assertThat(map(paths.get("/api/v1/identity/profile"))).containsKeys("get", "put");
    assertThat(responses(paths, "/api/v1/password/change"))
        .containsKeys("200", "400", "401", "403", "409", "429", "503");
    assertThat(responses(paths, "/api/v1/password/recovery/request")).containsKey("200");
    assertThat(responses(paths, "/api/v1/password/recovery/confirm")).containsKey("200");

    Map<String, Object> components = map(document.get("components"));
    Map<String, Object> schemas = map(components.get("schemas"));
    Map<String, Object> problem = map(schemas.get("Problem"));
    assertThat(list(problem.get("required"))).contains("type", "title", "status", "code");
    assertThat(map(map(problem.get("properties")).get("code")).get("pattern"))
        .isEqualTo("^[A-Z][A-Z0-9_]{0,63}$");

    Map<String, Object> register = map(schemas.get("RegisterRequest"));
    assertThat(register.get("additionalProperties")).isEqualTo(Boolean.FALSE);
    Map<String, Object> password = map(map(register.get("properties")).get("password"));
    assertThat(password.get("minLength")).isEqualTo(12);
    assertThat(password.get("maxLength")).isEqualTo(128);
    assertThat(password.get("description").toString()).contains("Unicode code-point policy");

    Map<String, Object> change = map(schemas.get("ChangePasswordRequest"));
    assertThat(change.get("additionalProperties")).isEqualTo(Boolean.FALSE);
    assertThat(map(change.get("properties"))).doesNotContainKey("refreshCredential");
    Map<String, Object> recovery = map(schemas.get("PasswordRecoveryConfirmRequest"));
    assertThat(map(map(recovery.get("properties")).get("code")).get("pattern"))
        .isEqualTo("^[0-9]{8}$");

    Map<String, Object> parameters = map(components.get("parameters"));
    assertThat(map(parameters.get("RequestId"))).containsEntry("name", "X-Request-Id");
    assertThat(Files.readString(Path.of("contracts/openapi.yaml")))
        .doesNotContain("X-HooshiX-Client-IP")
        .doesNotContain("X-Forwarded-For")
        .doesNotContain("minimum password length");
  }

  private static Map<String, Object> responses(Map<String, Object> paths, String path) {
    return map(map(map(paths.get(path)).get("post")).get("responses"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<String> list(Object value) {
    return (List<String>) value;
  }
}
