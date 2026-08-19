package com.sajtech.identity.application.registration.service;

import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

public final class PasswordNormalizer {
  private static final int TRANSPORT_SAFETY_MAX_UTF8_BYTES = 4096;

  public String normalize(String password) {
    if (password == null) {
      throw invalid();
    }
    String normalized = Normalizer.normalize(password, Normalizer.Form.NFC);
    if (normalized.isEmpty()
        || normalized.getBytes(StandardCharsets.UTF_8).length > TRANSPORT_SAFETY_MAX_UTF8_BYTES) {
      throw invalid();
    }
    return normalized;
  }

  private static RegistrationException invalid() {
    return new RegistrationException(
        RegistrationError.INVALID_ARGUMENT, "Password input is invalid");
  }
}
