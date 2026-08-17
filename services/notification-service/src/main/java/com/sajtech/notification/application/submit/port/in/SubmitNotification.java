package com.sajtech.notification.application.submit.port.in;

import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;
import com.sajtech.notification.application.submit.model.SubmitNotificationResult;

@FunctionalInterface
public interface SubmitNotification {
  SubmitNotificationResult submit(SubmitNotificationCommand command);
}
