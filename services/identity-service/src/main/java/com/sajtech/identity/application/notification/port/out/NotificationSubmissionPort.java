package com.sajtech.identity.application.notification.port.out;

import com.sajtech.identity.application.notification.model.NotificationOutboxRecord;
import com.sajtech.identity.application.registration.model.DecryptedHandoff;

public interface NotificationSubmissionPort {
  void submit(NotificationOutboxRecord record, DecryptedHandoff handoff);
}
