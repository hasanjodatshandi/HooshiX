package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;

public record QuotaRequest(
    QuotaOperation operation, CanonicalContact contact, byte[] clientAddress) {
  public QuotaRequest {
    clientAddress = clientAddress == null ? null : clientAddress.clone();
  }

  @Override
  public byte[] clientAddress() {
    return clientAddress == null ? null : clientAddress.clone();
  }
}
