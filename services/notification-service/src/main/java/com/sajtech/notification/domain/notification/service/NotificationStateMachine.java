package com.sajtech.notification.domain.notification.service;

import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class NotificationStateMachine {
  private static final Map<NotificationLifecycle, Set<NotificationLifecycle>> ALLOWED =
      Map.of(
          NotificationLifecycle.ACCEPTED,
          EnumSet.of(NotificationLifecycle.DISPATCHING, NotificationLifecycle.EXPIRED),
          NotificationLifecycle.DISPATCHING,
          EnumSet.of(
              NotificationLifecycle.RETRY_WAIT,
              NotificationLifecycle.PROVIDER_ACCEPTED,
              NotificationLifecycle.FAILED_PERMANENT,
              NotificationLifecycle.EXPIRED,
              NotificationLifecycle.DELIVERY_STATUS_UNKNOWN),
          NotificationLifecycle.RETRY_WAIT,
          EnumSet.of(NotificationLifecycle.DISPATCHING, NotificationLifecycle.EXPIRED),
          NotificationLifecycle.PROVIDER_ACCEPTED,
          EnumSet.of(
              NotificationLifecycle.DELIVERED,
              NotificationLifecycle.FAILED_PERMANENT,
              NotificationLifecycle.DELIVERY_STATUS_UNKNOWN));

  public void requireTransition(NotificationLifecycle from, NotificationLifecycle to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("Notification lifecycle is required");
    }
    if (from.isTerminal()) {
      throw new IllegalStateException("Terminal notification lifecycle is immutable");
    }
    if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
      throw new IllegalStateException("Invalid notification lifecycle transition");
    }
  }
}
