package com.sajtech.notification.application.result.port.out;

import com.sajtech.notification.application.result.model.NotificationResultOutboxRecord;

public interface NotificationResultCallbackPort {
  void report(NotificationResultOutboxRecord record);
}
