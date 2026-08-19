package com.sajtech.identity.domain.registration.valueobject;

import java.util.Objects;

public record CanonicalContact(
    RegistrationChannel channel, String canonicalValue, String deliveryValue) {
  public CanonicalContact {
    Objects.requireNonNull(channel, "channel");
    if (canonicalValue == null || canonicalValue.isBlank()) {
      throw new IllegalArgumentException("Canonical contact is required");
    }
    if (deliveryValue == null || deliveryValue.isBlank()) {
      throw new IllegalArgumentException("Delivery contact is required");
    }
  }
}
