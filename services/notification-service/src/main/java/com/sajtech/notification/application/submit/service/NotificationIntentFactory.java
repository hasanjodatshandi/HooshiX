package com.sajtech.notification.application.submit.service;

import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.CanonicalNotificationIntent;
import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;

public final class NotificationIntentFactory {
  private final String callerService;
  private final NotificationRecipientCanonicalizer recipients;
  private final NotificationLocaleNormalizer locales;

  public NotificationIntentFactory(
      String callerService,
      NotificationRecipientCanonicalizer recipients,
      NotificationLocaleNormalizer locales) {
    if (callerService == null || callerService.isBlank()) {
      throw new IllegalArgumentException("Caller service identity is required");
    }
    this.callerService = callerService;
    this.recipients = recipients;
    this.locales = locales;
  }

  public CanonicalNotificationIntent create(SubmitNotificationCommand command) {
    if (command.semanticContent().semanticType().isTimeBound() && command.messageNotAfter() == null) {
      throw new NotificationSubmissionException(
          NotificationSubmissionError.INVALID_NOTIFICATION_REQUEST,
          "Time-bound notification requires message_not_after");
    }
    return new CanonicalNotificationIntent(
        command.requestId(),
        callerService,
        command.channel(),
        recipients.canonicalize(command.channel(), command.recipient()),
        locales.normalize(command.locale()),
        command.semanticContent().semanticType(),
        command.semanticContent(),
        command.messageNotAfter());
  }
}
