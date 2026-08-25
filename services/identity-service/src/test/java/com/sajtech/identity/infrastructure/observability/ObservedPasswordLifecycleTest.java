package com.sajtech.identity.infrastructure.observability;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.application.password.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ObservedPasswordLifecycleTest {
  @Test
  void recordsOnlyBoundedOperationAndOutcomeLabelsForRejectedProof() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ObservedPasswordLifecycle observed =
        new ObservedPasswordLifecycle(
            command -> {
              throw new PasswordException(
                  PasswordError.INVALID_CREDENTIALS, "credential detail must not become a label");
            },
            command -> {},
            command -> {},
            ObservationRegistry.NOOP,
            meters);

    assertThatThrownBy(() -> observed.change(null)).isInstanceOf(PasswordException.class);

    var timer =
        meters
            .get("identity.password_lifecycle.duration")
            .tags("operation", "CHANGE", "outcome", "INVALID_CREDENTIALS")
            .timer();
    assertThat(timer.count()).isEqualTo(1);
    Set<String> tagKeys =
        timer.getId().getTags().stream().map(tag -> tag.getKey()).collect(Collectors.toSet());
    assertThat(tagKeys).containsExactlyInAnyOrder("operation", "outcome");
    assertThat(timer.getId().getTags().toString()).doesNotContain("credential detail");
  }
}
