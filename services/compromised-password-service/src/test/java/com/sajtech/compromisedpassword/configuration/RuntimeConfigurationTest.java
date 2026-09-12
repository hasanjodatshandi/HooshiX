package com.sajtech.compromisedpassword.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeConfigurationTest {
  @Test
  void transportAdmissionLeavesCapacityForImmediateApplicationOverloadRejection() {
    assertThat(RuntimeConfiguration.grpcTransportConcurrencyLimit(32)).isEqualTo(64);
  }
}
