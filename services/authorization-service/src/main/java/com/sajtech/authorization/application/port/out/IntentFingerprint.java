package com.sajtech.authorization.application.port.out;

public interface IntentFingerprint {
  byte[] fingerprint(String operation, String... canonicalParts);
}
