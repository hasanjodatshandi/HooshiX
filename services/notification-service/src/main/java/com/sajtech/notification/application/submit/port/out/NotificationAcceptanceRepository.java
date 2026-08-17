package com.sajtech.notification.application.submit.port.out;

import com.sajtech.notification.application.submit.model.AcceptedNotificationWrite;
import com.sajtech.notification.application.submit.model.StoredAcceptedNotification;
import java.util.Optional;
import java.util.UUID;

public interface NotificationAcceptanceRepository {
  Optional<StoredAcceptedNotification> findByCallerAndRequestId(
      String callerService, UUID requestId);

  void insert(AcceptedNotificationWrite notification);
}
