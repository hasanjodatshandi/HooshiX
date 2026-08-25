package com.sajtech.identity.application.externalidentity.port.out;

import com.sajtech.identity.application.registration.model.FingerprintDigest;

public interface ExternalIdentityFingerprintPort {
  FingerprintDigest digest(byte[] material);

  boolean matches(byte[] material, byte[] expected, String keyId, String version);
}
