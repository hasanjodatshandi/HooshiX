package com.sajtech.notification.application.submit.port.out;

import com.sajtech.notification.application.submit.model.FingerprintDigest;

public interface IntentFingerprintPort {
  FingerprintDigest compute(byte[] canonicalMaterial);

  boolean verify(
      byte[] canonicalMaterial,
      String fingerprintVersion,
      String fingerprintKeyId,
      byte[] expectedDigest);
}
