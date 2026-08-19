package com.sajtech.identity.application.authentication.port.out;

import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;

public interface LoginQuotaPort {
  void checkSource(byte[] clientAddress);

  void recordFailure(CanonicalContact contact);

  void recordSuccess(CanonicalContact contact);
}
