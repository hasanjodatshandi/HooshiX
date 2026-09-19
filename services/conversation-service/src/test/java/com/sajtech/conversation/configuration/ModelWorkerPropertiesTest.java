package com.sajtech.conversation.configuration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ModelWorkerPropertiesTest {
  @Test
  void validatesLeaseConcurrencyAndCircuitBounds() {
    assertThatThrownBy(
            () ->
                new ModelWorkerProperties(
                    null,
                    Duration.ofMillis(250),
                    Duration.ofSeconds(65),
                    Duration.ofSeconds(30),
                    4,
                    1,
                    20,
                    3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ModelWorkerProperties(
                    Path.of("key"),
                    Duration.ofMillis(250),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(30),
                    4,
                    1,
                    20,
                    3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ModelWorkerProperties(
                    Path.of("key"),
                    Duration.ofMillis(250),
                    Duration.ofSeconds(65),
                    Duration.ofSeconds(30),
                    2,
                    3,
                    20,
                    3))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
