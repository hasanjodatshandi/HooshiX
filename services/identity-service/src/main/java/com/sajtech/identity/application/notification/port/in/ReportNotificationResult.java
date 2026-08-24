package com.sajtech.identity.application.notification.port.in;

import com.sajtech.identity.application.notification.model.*;

public interface ReportNotificationResult {
  NotificationResultApplyOutcome report(NotificationTerminalResult result);
}
