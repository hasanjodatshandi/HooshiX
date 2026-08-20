package com.sajtech.webbff.configuration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    Map<String, String> routeAudiences) {
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
        || encryptionKeyMaximumStaleness.isZero())
      throw new IllegalArgumentException("Web BFF security material configuration is invalid");
    if (publicOrigin.getPort() != -1
        && (publicOrigin.getPort() < 1 || publicOrigin.getPort() > 65535))
      throw new IllegalArgumentException("Web BFF public origin port is invalid");
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
  }
}
