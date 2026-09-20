package com.sajtech.conversation.configuration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConversationPropertiesTest {
  @Test
  void rejectsMissingOrNonPositiveSecurityConfiguration() {
    assertThatThrownBy(
            () ->
                new ConversationProperties(
                    false,
                    0,
                    null,
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(1),
                    "dns:///authorization:9090",
                    4,
                    Path.of("jwt"),
                    "https://identity.internal",
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ConversationProperties(
                    false,
                    0,
                    Path.of("keys"),
                    Duration.ofSeconds(30),
                    Duration.ZERO,
                    "dns:///authorization:9090",
                    4,
                    Path.of("jwt"),
                    "https://identity.internal",
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ConversationProperties(
                    false,
                    0,
                    Path.of("keys"),
                    Duration.ofMinutes(1),
                    Duration.ofMinutes(1),
                    "dns:///authorization:9090",
                    4,
                    Path.of("jwt"),
                    "https://identity.internal",
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ConversationProperties(
                    false,
                    0,
                    Path.of("keys"),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(1),
                    "",
                    0,
                    null,
                    "",
                    Duration.ZERO,
                    Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
