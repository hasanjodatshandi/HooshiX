package com.sajtech.identity.domain.registration;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record CanonicalContact(ContactKind kind, String canonicalValue, String deliveryValue) {
  private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

  public CanonicalContact {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(canonicalValue, "canonicalValue");
    Objects.requireNonNull(deliveryValue, "deliveryValue");
  }

  public static CanonicalContact email(String raw) {
    String delivery = normalizeMailbox(raw);
    return new CanonicalContact(ContactKind.EMAIL, delivery.toLowerCase(Locale.ROOT), delivery);
  }

  public static CanonicalContact phone(String raw) {
    String value = Objects.requireNonNull(raw, "contact").trim();
    if (!E164.matcher(value).matches()) {
      throw new IllegalArgumentException("phone must be canonical E.164");
    }
    return new CanonicalContact(ContactKind.PHONE, value, value);
  }

  private static String normalizeMailbox(String raw) {
    String value = Normalizer.normalize(Objects.requireNonNull(raw, "contact").trim(), Normalizer.Form.NFC);
    if (value.isEmpty() || value.length() > 254 || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("invalid email mailbox");
    }
    int at = value.indexOf('@');
    if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
      throw new IllegalArgumentException("invalid email mailbox");
    }
    if (value.codePoints().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("invalid email mailbox");
    }
    return value;
  }
}
