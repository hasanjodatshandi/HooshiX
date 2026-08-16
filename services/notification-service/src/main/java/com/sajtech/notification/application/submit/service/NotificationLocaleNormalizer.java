package com.sajtech.notification.application.submit.service;

import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import java.util.Locale;
import java.util.Set;

public final class NotificationLocaleNormalizer {
  private static final Set<String> SUPPORTED = Set.of("en", "fa");

  public String normalize(String rawLocale) {
    if (rawLocale == null || rawLocale.isBlank()) {
      throw unsupported();
    }
    String normalized = rawLocale.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    String primary = normalized.split("-", 2)[0];
    if (!SUPPORTED.contains(primary)) {
      throw unsupported();
    }
    return primary;
  }

  private static NotificationSubmissionException unsupported() {
    return new NotificationSubmissionException(
        NotificationSubmissionError.UNSUPPORTED_LOCALE, "Notification locale is not supported");
  }
}
