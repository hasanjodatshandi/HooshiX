package com.sajtech.webbff.configuration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "web-bff")
public record WebBffProperties(
    boolean runtimeEnabled,
    boolean requireFetchMetadata,
    URI publicOrigin,
    String identityTarget,
    String authorizationTarget,
    String redisUri,
    Path locatorKeyRingPath,
    Path csrfKeyRingPath,
    Path refreshEncryptionKeyRingPath,
    Duration hmacKeyMaximumStaleness,
    Duration encryptionKeyMaximumStaleness,
    int identityMaximumConcurrentCalls,
    int authorizationMaximumConcurrentCalls,
    Map<String, String> routeAudiences,
    Path quotaKeyRingPath,
    OidcQuota oidcQuota,
    boolean googleOidcEnabled,
    URI googleAuthorizationEndpoint,
    URI googleTokenEndpoint,
    URI googleJwkSetUri,
    String googleClientId,
    Path googleClientSecretPath,
    int googleMaximumConcurrentCalls) {
  public WebBffProperties(
      boolean runtimeEnabled,
      boolean requireFetchMetadata,
      URI publicOrigin,
      String identityTarget,
      String authorizationTarget,
      String redisUri,
      Path locatorKeyRingPath,
      Path csrfKeyRingPath,
      Path refreshEncryptionKeyRingPath,
      Duration hmacKeyMaximumStaleness,
      Duration encryptionKeyMaximumStaleness,
      int identityMaximumConcurrentCalls,
      int authorizationMaximumConcurrentCalls,
      Map<String, String> routeAudiences) {
    this(
        runtimeEnabled,
        requireFetchMetadata,
        publicOrigin,
        identityTarget,
        authorizationTarget,
        redisUri,
        locatorKeyRingPath,
        csrfKeyRingPath,
        refreshEncryptionKeyRingPath,
        hmacKeyMaximumStaleness,
        encryptionKeyMaximumStaleness,
        identityMaximumConcurrentCalls,
        authorizationMaximumConcurrentCalls,
        routeAudiences,
        locatorKeyRingPath,
        new OidcQuota(10000, 1000, 30, locatorKeyRingPath),
        false,
        URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
        URI.create("https://oauth2.googleapis.com/token"),
        URI.create("https://www.googleapis.com/oauth2/v3/certs"),
        "disabled",
        locatorKeyRingPath,
        1);
  }

  @ConstructorBinding
  public WebBffProperties {
    if (publicOrigin == null
        || !"https".equalsIgnoreCase(publicOrigin.getScheme())
        || publicOrigin.getHost() == null
        || publicOrigin.getRawUserInfo() != null
        || publicOrigin.getRawQuery() != null
        || publicOrigin.getRawFragment() != null
        || (publicOrigin.getPath() != null
            && !publicOrigin.getPath().isEmpty()
            && !"/".equals(publicOrigin.getPath())))
      throw new IllegalArgumentException("Web BFF public origin is invalid");
    if (identityTarget == null
        || identityTarget.isBlank()
        || authorizationTarget == null
        || authorizationTarget.isBlank()
        || redisUri == null
        || redisUri.isBlank())
      throw new IllegalArgumentException("Web BFF dependency targets are required");
    if (locatorKeyRingPath == null
        || csrfKeyRingPath == null
        || refreshEncryptionKeyRingPath == null
        || hmacKeyMaximumStaleness == null
        || hmacKeyMaximumStaleness.isNegative()
        || hmacKeyMaximumStaleness.isZero()
        || encryptionKeyMaximumStaleness == null
        || encryptionKeyMaximumStaleness.isNegative()
        || encryptionKeyMaximumStaleness.isZero()
        || quotaKeyRingPath == null
        || oidcQuota == null)
      throw new IllegalArgumentException("Web BFF security material configuration is invalid");
    if (publicOrigin.getPort() != -1
        && (publicOrigin.getPort() < 1 || publicOrigin.getPort() > 65535))
      throw new IllegalArgumentException("Web BFF public origin port is invalid");
    if (identityMaximumConcurrentCalls < 1 || authorizationMaximumConcurrentCalls < 1)
      throw new IllegalArgumentException("Web BFF dependency concurrency limits must be positive");
    routeAudiences = Map.copyOf(routeAudiences == null ? Map.of() : routeAudiences);
    if (routeAudiences.entrySet().stream()
        .anyMatch(
            e ->
                e.getKey() == null
                    || !e.getKey().startsWith("/api/v1/")
                    || e.getValue() == null
                    || e.getValue().isBlank()
                    || e.getValue().contains("*")))
      throw new IllegalArgumentException("Web BFF route audience map is invalid");
    if (!URI.create("https://accounts.google.com/o/oauth2/v2/auth")
            .equals(googleAuthorizationEndpoint)
        || !URI.create("https://oauth2.googleapis.com/token").equals(googleTokenEndpoint)
        || !URI.create("https://www.googleapis.com/oauth2/v3/certs").equals(googleJwkSetUri)
        || googleClientId == null
        || googleClientId.isBlank()
        || googleClientId.length() > 255
        || googleClientSecretPath == null
        || googleMaximumConcurrentCalls < 1) {
      throw new IllegalArgumentException("Web BFF Google OIDC configuration is invalid");
    }
    if (googleOidcEnabled
        && !googleClientId.matches("[0-9A-Za-z._-]{1,220}[.]apps[.]googleusercontent[.]com")) {
      throw new IllegalArgumentException("Web BFF Google client ID is invalid");
    }
  }

  public record OidcQuota(
      int maxActiveBuckets,
      int maxNewBucketsPerMinute,
      int minimumMemoryHeadroomPercent,
      Path hostTimeStatusPath) {
    public OidcQuota {
      if (maxActiveBuckets < 1
          || maxNewBucketsPerMinute < 1
          || minimumMemoryHeadroomPercent < 30
          || minimumMemoryHeadroomPercent >= 100
          || hostTimeStatusPath == null) {
        throw new IllegalArgumentException("Web BFF OIDC quota configuration is invalid");
      }
    }
  }
}
