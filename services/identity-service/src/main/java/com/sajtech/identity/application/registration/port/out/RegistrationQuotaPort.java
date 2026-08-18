package com.sajtech.identity.application.registration.port.out;

import com.sajtech.identity.application.registration.model.RequestPurpose;
import com.sajtech.identity.domain.registration.CanonicalContact;

public interface RegistrationQuotaPort {
  void acquire(RequestPurpose purpose, CanonicalContact contact, String trustedClientIp);
}
