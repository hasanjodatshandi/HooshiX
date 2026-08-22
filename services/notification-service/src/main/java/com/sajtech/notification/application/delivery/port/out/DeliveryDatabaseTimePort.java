package com.sajtech.notification.application.delivery.port.out;

import java.time.Instant;

public interface DeliveryDatabaseTimePort {
  Instant now();
}
