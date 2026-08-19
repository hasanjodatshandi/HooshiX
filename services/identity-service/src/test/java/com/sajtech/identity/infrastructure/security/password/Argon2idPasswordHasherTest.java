package com.sajtech.identity.infrastructure.security.password;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Argon2idPasswordHasherTest {
  @Test
  void verifiesStoredHashAndUsesDummyProofForUnknownCredential() {
    Argon2idPasswordHasher hasher = new Argon2idPasswordHasher(1);
    String stored = hasher.hash("correct password");

    assertThat(stored).startsWith("$argon2id$");
    assertThat(hasher.matches("correct password", stored)).isTrue();
    assertThat(hasher.matches("wrong password", stored)).isFalse();
    assertThat(hasher.matches("wrong password", null)).isFalse();
  }
}
