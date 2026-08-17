package com.sajtech.notification.application.submit.service;

import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.net.IDN;
import java.text.Normalizer;
import java.util.Locale;

public final class NotificationRecipientCanonicalizer {
  private static final int MAX_EMAIL_LENGTH = 254;
  private static final String EMAIL_LOCAL_PATTERN = "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+";
  private static final String E164_PATTERN = "\\+[1-9][0-9]{7,14}";

  public String canonicalize(NotificationChannel channel, String rawRecipient) {
    if (rawRecipient == null) {
      throw invalidRecipient();
    }
    String normalized = Normalizer.normalize(rawRecipient.trim(), Normalizer.Form.NFC);
    if (containsControlOrWhitespace(normalized)) {
      throw invalidRecipient();
    }
    return switch (channel) {
      case EMAIL -> canonicalizeEmail(normalized);
      case SMS -> canonicalizePhone(normalized);
    };
  }

  private String canonicalizeEmail(String value) {
    if (value.length() > MAX_EMAIL_LENGTH
        || value.indexOf('<') >= 0
        || value.indexOf('>') >= 0
        || value.indexOf('(') >= 0
        || value.indexOf(')') >= 0
        || value.indexOf(',') >= 0
        || value.indexOf(';') >= 0
        || value.indexOf(':') >= 0) {
      throw invalidRecipient();
    }
    int at = value.lastIndexOf('@');
    if (at <= 0 || at != value.indexOf('@') || at == value.length() - 1) {
      throw invalidRecipient();
    }
    String local = value.substring(0, at);
    String domain = value.substring(at + 1);
    if (local.length() > 64 || !local.matches(EMAIL_LOCAL_PATTERN)) {
      throw invalidRecipient();
    }
    try {
      String asciiDomain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
      if (asciiDomain.isBlank() || asciiDomain.length() > 253 || !asciiDomain.contains(".")) {
        throw invalidRecipient();
      }
      return local + "@" + asciiDomain;
    } catch (IllegalArgumentException exception) {
      throw invalidRecipient();
    }
  }

  private String canonicalizePhone(String value) {
    if (!value.matches(E164_PATTERN)) {
      throw invalidRecipient();
    }
    return value;
  }

  private static boolean containsControlOrWhitespace(String value) {
    return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint) || Character.isWhitespace(codePoint));
  }

  private static NotificationSubmissionException invalidRecipient() {
    return new NotificationSubmissionException(
        NotificationSubmissionError.INVALID_NOTIFICATION_REQUEST, "Notification recipient is invalid");
  }
}
