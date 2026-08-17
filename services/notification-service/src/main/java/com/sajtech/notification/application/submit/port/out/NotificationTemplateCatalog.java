package com.sajtech.notification.application.submit.port.out;

import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.util.Optional;

public interface NotificationTemplateCatalog {
  Optional<NotificationTemplateVersion> findActive(
      NotificationChannel channel, NotificationSemanticType semanticType, String locale);
}
