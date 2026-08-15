package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record DatasetSettings(
        Path path,
        String expectedSha256,
        Instant acquiredAt,
        int formatVersion,
        int maxPrefixCardinality) {
    public DatasetSettings {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        String configuredPath = path.toString();
        if (configuredPath.indexOf('\0') >= 0
                || configuredPath.contains("?")
                || configuredPath.contains("#")) {
            throw new IllegalArgumentException("Dataset path must not contain SQLite URI controls");
        }
        if (!expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected SHA-256 must be lowercase hexadecimal");
        }
        if (formatVersion != 1) {
            throw new IllegalArgumentException("Unsupported dataset format version");
        }
        if (maxPrefixCardinality <= 0) {
            throw new IllegalArgumentException("Maximum prefix cardinality must be positive");
        }
        path = path.toAbsolutePath().normalize();
    }
}
