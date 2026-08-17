package com.sajtech.notification.application.submit.port.out;

import java.time.Instant;

@FunctionalInterface
public interface DatabaseTimePort {
  Instant now();
}
