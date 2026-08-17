package com.sajtech.notification.application.submit.port.out;

import com.sajtech.notification.application.submit.model.CanonicalNotificationIntent;
import com.sajtech.notification.application.submit.model.EncryptedDeliveryPayload;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.application.template.model.RenderedNotification;
import java.util.UUID;

public interface DeliveryEscrowPort {
  EncryptedDeliveryPayload encrypt(
      UUID notificationId,
      CanonicalNotificationIntent intent,
      NotificationTemplateVersion template,
      RenderedNotification rendered);
}
