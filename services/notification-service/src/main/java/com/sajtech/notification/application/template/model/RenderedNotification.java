package com.sajtech.notification.application.template.model;

public record RenderedNotification(String subject, String text, String html) {
  public RenderedNotification {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Rendered notification text is required");
    }
  }
}
