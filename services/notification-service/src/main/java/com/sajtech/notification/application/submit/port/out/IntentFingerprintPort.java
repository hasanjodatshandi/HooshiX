package com.sajtech.notification.application.submit.port.out;

import com.sajtech.notification.application.submit.model.FingerprintDigest;

public interface IntentFingerprintPort {
  FingerprintDigest compute(byte[] canonicalMaterial);

  boolean constantTimeEquals(byte[] left, byte[] right);
}
