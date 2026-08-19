package com.sajtech.identity.application.registration.service;

import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.domain.registration.valueobject.RegistrationProfile;
import java.text.Normalizer;

public final class ProfileCanonicalizer {
  private static final int MAX_CODE_POINTS = 120;

  public RegistrationProfile canonicalize(String firstName, String lastName, String fatherName) {
    return new RegistrationProfile(required(firstName), required(lastName), optional(fatherName));
  }

  private static String required(String value) {
    String normalized = normalize(value);
    if (normalized == null || normalized.isEmpty()) {
      throw invalid();
    }
    return normalized;
  }

  private static String optional(String value) {
    if (value == null) {
      return null;
    }
    String normalized = normalize(value);
    return normalized == null || normalized.isEmpty() ? null : normalized;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    if (normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS
        || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid();
    }
    return normalized;
  }

  private static RegistrationException invalid() {
    return new RegistrationException(RegistrationError.INVALID_ARGUMENT, "Profile name is invalid");
  }
}
