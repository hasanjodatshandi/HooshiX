package com.sajtech.identity.domain.registration;

import java.text.Normalizer;
import java.util.Objects;

public record RegistrationProfile(String firstName, String lastName, String fatherName) {
  public RegistrationProfile {
    firstName = requiredName(firstName, "firstName");
    lastName = requiredName(lastName, "lastName");
    fatherName = optionalName(fatherName);
  }

  private static String requiredName(String raw, String field) {
    String value = normalize(Objects.requireNonNull(raw, field));
    int count = value.codePointCount(0, value.length());
    if (count < 1 || count > 120) {
      throw new IllegalArgumentException(field + " must contain 1..120 code points");
    }
    return value;
  }

  private static String optionalName(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = normalize(raw);
    if (value.codePointCount(0, value.length()) > 120) {
      throw new IllegalArgumentException("fatherName must contain at most 120 code points");
    }
    return value;
  }

  private static String normalize(String raw) {
    String value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("name contains a control character");
    }
    return value;
  }
}
