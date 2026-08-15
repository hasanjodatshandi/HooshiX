package com.sajtech.compromisedpassword.domain.lookup.valueobject;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CompromisedHashMatchTest {
  @Test
  void rejectsInvalidSuffixAndNonPositiveCount() {
    assertThatThrownBy(() -> new CompromisedHashMatch("A".repeat(34), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CompromisedHashMatch("A".repeat(35), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
