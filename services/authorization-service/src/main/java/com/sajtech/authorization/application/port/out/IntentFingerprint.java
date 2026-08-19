package com.sajtech.authorization.application.port.out;

import com.sajtech.authorization.application.model.FingerprintDigest;

public interface IntentFingerprint {
  FingerprintDigest fingerprint(String operation, String... canonicalParts);
}
