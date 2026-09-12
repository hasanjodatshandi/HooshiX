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
                    false, null, Duration.ofSeconds(30), Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ConversationProperties(
                    false, Path.of("keys"), Duration.ofSeconds(30), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ConversationProperties(
                    false, Path.of("keys"), Duration.ofMinutes(1), Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
