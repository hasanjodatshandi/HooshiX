package com.sajtech.notification.application.submit.service;

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
      throw new IllegalArgumentException("Caller service is required");
    }
    this.callerService = callerService.trim();
    this.recipients = recipients;
    this.locales = locales;
  }

  public CanonicalNotificationIntent create(SubmitNotificationCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("Notification command is required");
    }
    String canonicalRecipient = recipients.canonicalize(command.channel(), command.recipient());
    String canonicalLocale = locales.normalize(command.locale());
    return new CanonicalNotificationIntent(
        callerService,
        command.requestId(),
        command.channel(),
        canonicalRecipient,
        canonicalLocale,
        command.messageNotAfter(),
        command.semanticContent().semanticType(),
        command.semanticContent());
  }
}
