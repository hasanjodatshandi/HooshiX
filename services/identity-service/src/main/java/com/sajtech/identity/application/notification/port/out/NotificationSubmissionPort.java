package com.sajtech.identity.application.notification.port.out;

import com.sajtech.identity.application.notification.model.NotificationOutboxRecord;
import com.sajtech.identity.application.registration.model.DecryptedHandoff;
import java.util.UUID;

public interface NotificationSubmissionPort {
  UUID submit(NotificationOutboxRecord record, DecryptedHandoff handoff);
}
