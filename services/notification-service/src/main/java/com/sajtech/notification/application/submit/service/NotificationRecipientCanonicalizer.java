package com.sajtech.notification.application.submit.service;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

public final class NotificationRecipientCanonicalizer {
  private static final Pattern LOCAL_PART =
      Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]{1,64}");
  private static final Pattern PHONE = Pattern.compile("\\+[1-9][0-9]{7,14}");

  public String canonicalize(NotificationChannel channel, String recipient) {
    if (channel == null || recipient == null || recipient.isBlank()) {
      throw new IllegalArgumentException("Notification recipient is required");
    }
    return switch (channel) {
      case EMAIL -> canonicalEmail(recipient);
      case SMS -> canonicalPhone(recipient);
    };
  }

  private static String canonicalEmail(String raw) {
    String trimmed = raw.trim();
    int at = trimmed.lastIndexOf('@');
    if (at <= 0 || at == trimmed.length() - 1 || trimmed.indexOf('@') != at) {
      throw new IllegalArgumentException("Invalid email recipient");
    }
    String local = trimmed.substring(0, at);
    String domain = trimmed.substring(at + 1);
    if (!LOCAL_PART.matcher(local).matches()) {
      throw new IllegalArgumentException("Invalid email local-part");
    }
    String asciiDomain;
    try {
      asciiDomain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException invalidDomain) {
      throw new IllegalArgumentException("Invalid email domain", invalidDomain);
    }
    if (asciiDomain.length() > 253
        || asciiDomain.startsWith(".")
        || asciiDomain.endsWith(".")
        || !asciiDomain.contains(".")) {
      throw new IllegalArgumentException("Invalid email domain");
    }
    return local + "@" + asciiDomain;
  }

  private static String canonicalPhone(String raw) {
    String trimmed = raw.trim();
    StringBuilder canonical = new StringBuilder(trimmed.length());
    for (int index = 0; index < trimmed.length(); index++) {
      char character = trimmed.charAt(index);
      if (index == 0 && character == '+') {
        canonical.append(character);
      } else if (Character.isDigit(character)) {
        canonical.append(character);
      } else if (character == ' ' || character == '-' || character == '(' || character == ')') {
        // Presentation separators are intentionally ignored.
      } else {
        throw new IllegalArgumentException("Invalid phone recipient");
      }
    }
    String result = canonical.toString();
    if (!PHONE.matcher(result).matches()) {
      throw new IllegalArgumentException("Phone recipient must be canonical E.164");
    }
    return result;
  }
}
