package com.sajtech.identity.application.registration.service;

import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ContactCanonicalizer {
  private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{1,14}$");

  public CanonicalContact canonicalize(RegistrationChannel channel, String input) {
    if (channel == null || input == null) {
      throw invalid();
    }
    String value = input.strip();
    return switch (channel) {
      case EMAIL -> email(value);
      case PHONE -> phone(value);
    };
  }

  private static CanonicalContact email(String value) {
    if (value.isEmpty() || value.length() > 254 || containsControlOrWhitespace(value)) {
      throw invalid();
    }
    int at = value.indexOf('@');
    if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
      throw invalid();
    }
    String local = value.substring(0, at);
    String domain = value.substring(at + 1);
    if (local.startsWith(".")
        || local.endsWith(".")
        || local.contains("..")
        || domain.startsWith(".")
        || domain.endsWith(".")
        || domain.contains("..")) {
      throw invalid();
    }
    if (local.indexOf('<') >= 0
        || local.indexOf('>') >= 0
        || local.indexOf('(') >= 0
        || local.indexOf(')') >= 0
        || local.indexOf(',') >= 0
        || local.indexOf(';') >= 0
        || local.indexOf(':') >= 0
        || local.indexOf('"') >= 0) {
      throw invalid();
    }
    return new CanonicalContact(RegistrationChannel.EMAIL, value.toLowerCase(Locale.ROOT), value);
  }

  private static CanonicalContact phone(String value) {
    if (!PHONE.matcher(value).matches()) {
      throw invalid();
    }
    return new CanonicalContact(RegistrationChannel.PHONE, value, value);
  }

  private static boolean containsControlOrWhitespace(String value) {
    return value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c));
  }

  private static RegistrationException invalid() {
    return new RegistrationException(RegistrationError.INVALID_ARGUMENT, "Contact is invalid");
  }
}
