package com.sajtech.notification.application.delivery.model;

public record DecryptedDeliveryPayload(
    String recipient, String subject, String text, String html) {}
