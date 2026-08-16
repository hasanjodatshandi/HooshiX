package com.sajtech.notification.infrastructure.security.fingerprint;

import com.sajtech.notification.application.submit.model.FingerprintDigest;
import com.sajtech.notification.application.submit.port.out.IntentFingerprintPort;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.notification.infrastructure.security.keyring.KeyRingMaterial;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

public final class FileBackedHmacIntentFingerprint implements IntentFingerprintPort {
  public static final String VERSION = "fingerprint-v1";

  private final FileBackedKeyRing keyRing;

  public FileBackedHmacIntentFingerprint(FileBackedKeyRing keyRing) {
    this.keyRing = keyRing;
  }

  @Override
  public FingerprintDigest compute(byte[] canonicalMaterial) {
    KeyRingMaterial active = keyRing.activeKey();
    return new FingerprintDigest(
        VERSION, active.keyId(), hmac(active.key(), canonicalMaterial));
  }

  @Override
  public boolean verify(
      byte[] canonicalMaterial,
      String fingerprintVersion,
      String fingerprintKeyId,
      byte[] expectedDigest) {
    if (!VERSION.equals(fingerprintVersion)
        || fingerprintKeyId == null
        || expectedDigest == null
        || expectedDigest.length != 32) {
      return false;
    }
    SecretKey verificationKey = keyRing.key(fingerprintKeyId);
    byte[] actual = hmac(verificationKey, canonicalMaterial);
    return MessageDigest.isEqual(expectedDigest, actual);
  }

  private static byte[] hmac(SecretKey key, byte[] canonicalMaterial) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(key);
      return hmac.doFinal(canonicalMaterial);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
    }
  }
}
