package com.sajtech.identity.application.registration.service;

import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.net.IDN;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ContactCanonicalizer {
  private static final int MAX_EMAIL_LENGTH = 254;
  private static final Pattern EMAIL_LOCAL =
      Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:[.][A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
  private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

  public CanonicalContact canonicalize(RegistrationChannel channel, String input) {
    if (channel == null || input == null) {
      throw invalid();
    }
    String value = Normalizer.normalize(input.strip(), Normalizer.Form.NFC);
    return switch (channel) {
      case EMAIL -> email(value);
      case PHONE -> phone(value);
    };
  }

  private static CanonicalContact email(String value) {
    if (value.isEmpty()
        || value.length() > MAX_EMAIL_LENGTH
        || containsControlOrWhitespace(value)) {
      throw invalid();
    }
    int at = value.indexOf('@');
    if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
      throw invalid();
    }
    String local = value.substring(0, at);
    String domain = value.substring(at + 1);
    if (local.length() > 64 || !EMAIL_LOCAL.matcher(local).matches()) {
      throw invalid();
    }
    try {
      String asciiDomain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
      if (asciiDomain.isBlank()
          || asciiDomain.length() > 253
          || !asciiDomain.contains(".")
          || (local.length() + 1 + asciiDomain.length()) > MAX_EMAIL_LENGTH) {
        throw invalid();
      }
      String delivery = local + "@" + asciiDomain;
      return new CanonicalContact(
          RegistrationChannel.EMAIL, delivery.toLowerCase(Locale.ROOT), delivery);
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private static CanonicalContact phone(String value) {
    if (!PHONE.matcher(value).matches()) {
      throw invalid();
    }
    return new CanonicalContact(RegistrationChannel.PHONE, value, value);
  }

  private static boolean containsControlOrWhitespace(String value) {
    return value
        .codePoints()
        .anyMatch(
            c ->
                Character.isISOControl(c)
                    || Character.isWhitespace(c)
                    || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.SPACE_SEPARATOR);
  }

  private static RegistrationException invalid() {
    return new RegistrationException(RegistrationError.INVALID_ARGUMENT, "Contact is invalid");
  }
}
