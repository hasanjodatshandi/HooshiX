package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class OpenApiControllerContractTest {
  private static final Path CONTRACT = Path.of("contracts/openapi.yaml");
  private static final String CONTROLLER_PACKAGE = "com.sajtech.webbff.interfaces.http";
  private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");

  @Test
  void canonicalOpenApiHasAnExactDynamicallyDiscoveredControllerRouteSet() throws Exception {
    Map<String, Object> document = document();
    Set<Route> openApiRoutes = openApiRoutes(document);
    Set<Route> controllerRoutes = controllerRoutes();

    assertThat(document.get("openapi")).isEqualTo("3.1.0");
    assertThat(map(document.get("info")).get("version")).isEqualTo("1.5.0");
    assertThat(openApiRoutes).hasSize(57).containsExactlyInAnyOrderElementsOf(controllerRoutes);
  }

  @Test
  void contractIsStrictVersionedAndProvidesConsumableExamplesWithoutInternalInputs()
      throws Exception {
    Map<String, Object> document = document();
    Map<String, Object> components = map(document.get("components"));
    Map<String, Object> schemas = map(components.get("schemas"));
    Map<String, Object> paths = map(document.get("paths"));
    Set<String> operationIds = new HashSet<>();

    assertThat(map(components.get("securitySchemes"))).containsKey("BrowserSession");
    assertThat(map(components.get("parameters")))
        .doesNotContainKeys("X-HooshiX-Client-IP", "X-Forwarded-For", "Authorization");
    assertThat(Files.readString(CONTRACT))
        .doesNotContain("X-HooshiX-Client-IP")
        .doesNotContain("X-Forwarded-For")
        .doesNotContain("refreshCredential")
        .doesNotContain("accessToken")
        .doesNotContain("clientSecret");

    for (Map.Entry<String, Object> path : paths.entrySet()) {
      for (Map.Entry<String, Object> operation : map(path.getValue()).entrySet()) {
        if (!HTTP_METHODS.contains(operation.getKey())) continue;
        Map<String, Object> value = map(operation.getValue());
        assertThat(value.get("operationId")).isInstanceOf(String.class);
        assertThat(operationIds.add((String) value.get("operationId"))).isTrue();
        assertThat(value.get("x-hooshix-consumer-example")).isInstanceOf(Map.class);

        if (value.containsKey("requestBody")) {
          assertHasStandardExample(
              mediaSchema(map(value.get("requestBody")), schemas),
              path.getKey() + "#" + operation.getKey() + " request");
        }
        Map<String, Object> responses = map(value.get("responses"));
        assertThat(responses).isNotEmpty();
        for (Map.Entry<String, Object> response : responses.entrySet()) {
          if (!response.getKey().startsWith("2") && !response.getKey().startsWith("3")) {
            assertThat(map(response.getValue()).get("$ref")).isInstanceOf(String.class);
            continue;
          }
          Map<String, Object> responseValue = map(response.getValue());
          Map<String, Object> responseContent =
              responseValue.get("content") == null ? Map.of() : map(responseValue.get("content"));
          if (!responseContent.isEmpty()) {
            assertHasStandardExample(
                mediaSchema(responseValue, schemas),
                path.getKey() + "#" + operation.getKey() + " response " + response.getKey());
          }
        }
      }
    }

    for (Map.Entry<String, Object> schema : schemas.entrySet()) {
      Map<String, Object> value = map(schema.getValue());
      if ("object".equals(value.get("type"))) {
        assertThat(value.get("additionalProperties")).as(schema.getKey()).isEqualTo(Boolean.FALSE);
        assertThat(value).containsKey("example");
      }
    }
    Map<String, Object> problem = map(schemas.get("Problem"));
    assertThat(list(problem.get("required"))).contains("type", "title", "status", "code");
    assertThat(problem).containsKey("example");
  }

  @Test
  void authenticatedUnsafeRoutesDeclareSessionBoundCsrfAndAnonymousRoutesOptOutExplicitly()
      throws Exception {
    Map<String, Object> document = document();
    List<Object> defaultSecurity = list(document.get("security"));
    assertThat(defaultSecurity).isNotEmpty();
    Set<String> anonymousOperations =
        Set.of(
            "/api/v1/auth/session/bootstrap#post",
            "/api/v1/auth/session/csrf#post",
            "/api/v1/identity/registration#post",
            "/api/v1/identity/registration/resend#post",
            "/api/v1/identity/registration/confirm#post",
            "/api/v1/password/recovery/request#post",
            "/api/v1/password/recovery/confirm#post");
    for (Map.Entry<String, Object> path : map(document.get("paths")).entrySet()) {
      for (Map.Entry<String, Object> operation : map(path.getValue()).entrySet()) {
        if (!Set.of("post", "put", "delete", "patch").contains(operation.getKey())) continue;
        Map<String, Object> value = map(operation.getValue());
        String key = path.getKey() + "#" + operation.getKey();
        if (anonymousOperations.contains(key)) {
          assertThat(list(value.get("security"))).isEmpty();
          assertThat(parameterRefs(value))
              .as(key)
              .contains("#/components/parameters/OptionalCsrfToken");
        } else {
          List<Object> effectiveSecurity =
              value.containsKey("security") ? list(value.get("security")) : defaultSecurity;
          assertThat(effectiveSecurity).as(key).isNotEmpty();
          assertThat(parameterRefs(value)).contains("#/components/parameters/CsrfToken");
        }
      }
    }

    for (Map.Entry<String, Object> path : map(document.get("paths")).entrySet()) {
      for (Map.Entry<String, Object> operation : map(path.getValue()).entrySet()) {
        if (!HTTP_METHODS.contains(operation.getKey())) continue;
        Map<String, Object> value = map(operation.getValue());
        String key = path.getKey() + "#" + operation.getKey();
        List<Object> effectiveSecurity =
            value.containsKey("security") ? list(value.get("security")) : defaultSecurity;
        if (anonymousOperations.contains(key)) {
          assertThat(effectiveSecurity).as(key).isEmpty();
        } else {
          assertThat(effectiveSecurity).as(key).isNotEmpty();
        }
      }
    }
  }

  @Test
  void schemaValidationMatchesTheImplementedPublicBoundary() throws Exception {
    Map<String, Object> components = map(document().get("components"));
    Map<String, Object> schemas = map(components.get("schemas"));
    Map<String, Object> parameters = map(components.get("parameters"));

    assertThat(map(map(parameters.get("RequestId")).get("schema")).get("pattern"))
        .isEqualTo("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    assertThat(map(schemas.get("UuidV4")).get("pattern"))
        .isEqualTo("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    assertThat(list(map(schemas.get("MfaProofRequest")).get("oneOf"))).hasSize(2);
    assertThat(map(map(parameters.get("PageSize")).get("schema")))
        .containsEntry("minimum", 1)
        .containsEntry("maximum", 200)
        .containsEntry("default", 50);

    assertStringBounds(schemas, "LocalLoginRequest", "contact", 1, 254);
    assertStringBounds(schemas, "LocalLoginRequest", "password", 1, 4096);
    assertStringBounds(schemas, "CreateTenantRequest", "name", 1, 120);
    assertStringBounds(schemas, "CreateTenantRequest", "slug", 3, 63);
    assertThat(property(schemas, "CreateTenantRequest", "slug").get("pattern"))
        .isEqualTo("^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$");
    assertStringBounds(schemas, "RegisterRequest", "password", 12, 128);
    assertStringBounds(schemas, "ChangePasswordRequest", "currentPassword", 1, 4096);
    assertStringBounds(schemas, "ChangePasswordRequest", "newPassword", 12, 128);
    assertThat(property(schemas, "ConfirmRequest", "code").get("pattern")).isEqualTo("^[0-9]{8}$");
    assertThat(property(schemas, "OverrideRequest", "decision").get("enum"))
        .isEqualTo(List.of("GRANT", "DENY"));
    assertThat(property(schemas, "CreateRoleRequest", "permissionKeys"))
        .containsEntry("maxItems", 200);
    assertThat(map(property(schemas, "CreateRoleRequest", "permissionKeys").get("items")))
        .containsEntry("minLength", 1)
        .containsEntry("maxLength", 128);
    assertStringBounds(schemas, "ReasonRequest", "reason", 1, 500);
    assertThat(property(schemas, "ProfileResponse", "id").get("$ref"))
        .isEqualTo("#/components/schemas/UuidV4");
    assertThat(property(schemas, "ContactResponse", "id").get("$ref"))
        .isEqualTo("#/components/schemas/UuidV4");
    assertThat(property(schemas, "CreatedContactResponse", "id").get("$ref"))
        .isEqualTo("#/components/schemas/UuidV4");

    Map<String, Object> csrf = map(map(parameters.get("CsrfToken")).get("schema"));
    assertThat(csrf).containsEntry("minLength", 32).containsEntry("maxLength", 512);
    assertThat(map(parameters.get("OptionalCsrfToken"))).containsEntry("required", false);
    assertThat(map(map(parameters.get("OptionalCsrfToken")).get("schema")))
        .containsEntry("minLength", 32)
        .containsEntry("maxLength", 512);
  }

  private static Object mediaSchema(
      Map<String, Object> bodyOrContent, Map<String, Object> schemas) {
    Map<String, Object> content = map(bodyOrContent.get("content"));
    Map<String, Object> media = map(content.get("application/json"));
    Map<String, Object> schema = map(media.get("schema"));
    Object reference = schema.get("$ref");
    return reference == null
        ? schema
        : schemas.get(((String) reference).substring("#/components/schemas/".length()));
  }

  private static void assertHasStandardExample(Object schema, String description) {
    assertThat(map(schema)).as(description).containsKey("example");
  }

  private static Set<String> parameterRefs(Map<String, Object> operation) {
    Set<String> refs = new HashSet<>();
    for (Object parameter : listOrEmpty(operation.get("parameters"))) {
      Object ref = map(parameter).get("$ref");
      if (ref != null) refs.add((String) ref);
    }
    return refs;
  }

  private static Set<Route> openApiRoutes(Map<String, Object> document) {
    Set<Route> routes = new TreeSet<>();
    for (Map.Entry<String, Object> path : map(document.get("paths")).entrySet()) {
      for (String method : map(path.getValue()).keySet()) {
        if (HTTP_METHODS.contains(method))
          routes.add(new Route(method.toUpperCase(Locale.ROOT), path.getKey()));
      }
    }
    return routes;
  }

  private static Set<Route> controllerRoutes() throws Exception {
    Set<Route> routes = new TreeSet<>();
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    for (var candidate : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
      Class<?> controller = Class.forName(Objects.requireNonNull(candidate.getBeanClassName()));
      RequestMapping controllerMapping =
          AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping methodMapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (methodMapping == null) continue;
        assertThat(methodMapping.method()).as(method.toGenericString()).isNotEmpty();
        for (String base : paths(controllerMapping)) {
          for (String endpoint : paths(methodMapping)) {
            for (RequestMethod httpMethod : methodMapping.method()) {
              routes.add(new Route(httpMethod.name(), normalize(base, endpoint)));
            }
          }
        }
      }
    }
    return routes;
  }

  private static String[] paths(RequestMapping annotation) {
    if (annotation == null) return new String[] {""};
    String[] path = annotation.path().length == 0 ? annotation.value() : annotation.path();
    return path.length == 0 ? new String[] {""} : path;
  }

  private static String normalize(String base, String endpoint) {
    String joined = (base + "/" + endpoint).replaceAll("/+", "/");
    return joined.endsWith("/") && joined.length() > 1
        ? joined.substring(0, joined.length() - 1)
        : joined;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> document() throws Exception {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    try (InputStream input = Files.newInputStream(CONTRACT)) {
      return (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    assertThat(value).isInstanceOf(List.class);
    return (List<Object>) value;
  }

  private static List<Object> listOrEmpty(Object value) {
    return value == null ? List.of() : list(value);
  }

  private static Map<String, Object> property(
      Map<String, Object> schemas, String schema, String property) {
    return map(map(map(schemas.get(schema)).get("properties")).get(property));
  }

  private static void assertStringBounds(
      Map<String, Object> schemas, String schema, String property, int minimum, int maximum) {
    assertThat(property(schemas, schema, property))
        .containsEntry("minLength", minimum)
        .containsEntry("maxLength", maximum);
  }

  private record Route(String method, String path) implements Comparable<Route> {
    @Override
    public int compareTo(Route other) {
      int byMethod = method.compareTo(other.method);
      return byMethod == 0 ? path.compareTo(other.path) : byMethod;
    }
  }
}
