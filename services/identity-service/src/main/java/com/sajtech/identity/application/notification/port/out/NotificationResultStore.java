package com.sajtech.identity.application.notification.port.out;

import com.sajtech.identity.application.notification.model.*;

public interface NotificationResultStore {
  NotificationResultApplyOutcome apply(NotificationTerminalResult result);
}
