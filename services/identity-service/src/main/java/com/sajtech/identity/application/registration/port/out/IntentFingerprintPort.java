package com.sajtech.identity.application.registration.port.out;

import com.sajtech.identity.application.registration.model.CommandDedupRecord;
import com.sajtech.identity.application.registration.model.FingerprintDigest;

public interface IntentFingerprintPort {
  FingerprintDigest digest(byte[] material);

  boolean matches(byte[] material, CommandDedupRecord stored);
}
