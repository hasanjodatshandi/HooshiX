package com.sajtech.notification.application.submit.model;

public record EncryptedDeliveryPayload(
    int formatVersion,
    String keyId,
    EncryptedField recipient,
    EncryptedField subject,
    EncryptedField text,
    EncryptedField html) {
  public EncryptedDeliveryPayload {
    if (formatVersion != 1
        || keyId == null
        || keyId.isBlank()
        || recipient == null
        || text == null) {
      throw new IllegalArgumentException("Encrypted delivery payload is incomplete");
    }
  }
}
