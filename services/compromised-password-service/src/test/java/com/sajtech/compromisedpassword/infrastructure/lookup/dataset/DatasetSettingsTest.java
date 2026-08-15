package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DatasetSettingsTest {
  private static final String SHA256 = "a".repeat(64);

  @Test
  void rejectsSqliteUriControlsInConfiguredPath() {
    assertThatThrownBy(
            () ->
                new DatasetSettings(
                    Path.of("/data/corpus.sqlite?mode=rw"), SHA256, Instant.EPOCH, 1, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("URI controls");

    assertThatThrownBy(
            () ->
                new DatasetSettings(
                    Path.of("/data/corpus.sqlite#fragment"), SHA256, Instant.EPOCH, 1, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("URI controls");
  }
}
