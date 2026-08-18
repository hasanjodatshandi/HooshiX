package com.sajtech.identity.application.notificationhandoff.port.out;

import com.sajtech.identity.application.registration.OutboxPayload;
import java.util.UUID;

public interface NotificationHandoffPort {
  void submit(UUID requestId, OutboxPayload payload);
}
