package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import java.nio.file.Path;
import java.util.Objects;

public record DatasetSettings(
    Path path,
    Path manifestPath,
    String expectedManifestSha256,
    String requiredSourceKind,
    int maxPrefixCardinality,
    long maxSerializedResponseBytes) {
  public DatasetSettings {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(manifestPath, "manifestPath");
    Objects.requireNonNull(expectedManifestSha256, "expectedManifestSha256");
    Objects.requireNonNull(requiredSourceKind, "requiredSourceKind");
    validatePath(path, "Dataset");
    validatePath(manifestPath, "Manifest");
    if (path.toAbsolutePath().normalize().equals(manifestPath.toAbsolutePath().normalize())) {
      throw new IllegalArgumentException("Dataset and manifest paths must differ");
    }
    if (!expectedManifestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Expected manifest SHA-256 is invalid");
    }
    if (!requiredSourceKind.matches("[A-Z0-9_]{1,64}")) {
      throw new IllegalArgumentException("Required source kind is invalid");
    }
    if (maxPrefixCardinality <= 0) {
      throw new IllegalArgumentException("Maximum prefix cardinality must be positive");
    }
    if (maxSerializedResponseBytes <= 0) {
      throw new IllegalArgumentException("Maximum serialized response bytes must be positive");
    }
    path = path.toAbsolutePath().normalize();
    manifestPath = manifestPath.toAbsolutePath().normalize();
  }

  private static void validatePath(Path path, String label) {
    String configuredPath = path.toString();
    if (configuredPath.indexOf('\0') >= 0
        || configuredPath.contains("%")
        || configuredPath.contains("?")
        || configuredPath.contains("#")) {
      throw new IllegalArgumentException(label + " path must not contain SQLite URI controls");
    }
  }
}
