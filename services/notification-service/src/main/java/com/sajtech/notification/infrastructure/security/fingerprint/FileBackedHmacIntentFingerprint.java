package com.sajtech.notification.infrastructure.security.fingerprint;

import com.sajtech.notification.application.submit.model.FingerprintDigest;
import com.sajtech.notification.application.submit.port.out.IntentFingerprintPort;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.notification.infrastructure.security.keyring.FingerprintKey;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class FileBackedHmacIntentFingerprint implements IntentFingerprintPort {
  private static final String VERSION = "hmac-sha256-v1";
  private final FileBackedKeyRing keyRing;

  public FileBackedHmacIntentFingerprint(FileBackedKeyRing keyRing) {
    this.keyRing = keyRing;
  }

  @Override
  public FingerprintDigest compute(byte[] canonicalMaterial) {
    FingerprintKey key = keyRing.activeFingerprintKey();
    return new FingerprintDigest(VERSION, key.keyId(), digest(canonicalMaterial, key));
  }

  @Override
  public boolean verify(
      byte[] canonicalMaterial,
      String fingerprintVersion,
      String fingerprintKeyId,
      byte[] expectedDigest) {
    if (!VERSION.equals(fingerprintVersion) || fingerprintKeyId == null || expectedDigest == null) {
      return false;
    }
    FingerprintKey key = keyRing.fingerprintKey(fingerprintKeyId);
    return MessageDigest.isEqual(expectedDigest, digest(canonicalMaterial, key));
  }

  private static byte[] digest(byte[] canonicalMaterial, FingerprintKey key) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(key.keyBytes(), "HmacSHA256"));
      return hmac.doFinal(canonicalMaterial);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Notification intent fingerprinting failed", failure);
    }
  }
}
