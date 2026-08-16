package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DatasetSettingsTest {
  private static final String SHA256 = "a".repeat(64);

  @Test
  void rejectsSqliteUriControlsInConfiguredPaths() {
    assertThatThrownBy(
            () ->
                new DatasetSettings(
                    Path.of("/data/corpus.sqlite?mode=rw"),
                    Path.of("/data/release-manifest.json"),
                    SHA256,
                    "GENERATED_TEST_FIXTURE",
                    1,
                    10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("URI controls");

    assertThatThrownBy(
            () ->
                new DatasetSettings(
                    Path.of("/data/corpus%2fshadow.sqlite"),
                    Path.of("/data/release-manifest.json"),
                    SHA256,
                    "GENERATED_TEST_FIXTURE",
                    1,
                    10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("URI controls");

    assertThatThrownBy(
            () ->
                new DatasetSettings(
                    Path.of("/data/corpus.sqlite"),
                    Path.of("/data/release-manifest.json#fragment"),
                    SHA256,
                    "GENERATED_TEST_FIXTURE",
                    1,
                    10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("URI controls");
  }

  @Test
  void rejectsInvalidExpectedManifestDigest() {
    assertThatThrownBy(
            () ->
                new DatasetSettings(
                    Path.of("/data/corpus.sqlite"),
                    Path.of("/data/release-manifest.json"),
                    "ABC",
                    "GENERATED_TEST_FIXTURE",
                    1,
                    10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("manifest SHA-256");
  }
}
