package com.sajtech.identity.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sajtech.identity.application.mfa.MfaError;
import com.sajtech.identity.application.mfa.MfaException;
import com.sajtech.identity.application.mfa.port.in.CompleteMfaAuthentication;
import com.sajtech.identity.application.mfa.port.in.MfaManagement;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ObservedMfaTest {
  @Test
  void recordsProofRejectionWithOnlyBoundedOperationAndOutcomeLabels() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CompleteMfaAuthentication completion = mock(CompleteMfaAuthentication.class);
    when(completion.complete(any()))
        .thenThrow(new MfaException(MfaError.INVALID_PROOF, "sensitive proof detail"));
    ObservedMfa observed =
        new ObservedMfa(mock(MfaManagement.class), completion, ObservationRegistry.NOOP, meters);

    assertThatThrownBy(() -> observed.complete(null)).isInstanceOf(MfaException.class);

    var timer =
        meters
            .get("identity.mfa.duration")
            .tags("operation", "COMPLETE_AUTHENTICATION", "outcome", "INVALID_PROOF")
            .timer();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(meters.get("identity.mfa.proof_rejections").counter().count()).isEqualTo(1);
    assertThat(meters.get("identity.mfa.in_flight").gauge().value()).isZero();
    Set<String> labels =
        timer.getId().getTags().stream().map(tag -> tag.getKey()).collect(Collectors.toSet());
    assertThat(labels).containsExactlyInAnyOrder("operation", "outcome");
    assertThat(timer.getId().getTags().toString()).doesNotContain("sensitive proof detail");
  }
}
