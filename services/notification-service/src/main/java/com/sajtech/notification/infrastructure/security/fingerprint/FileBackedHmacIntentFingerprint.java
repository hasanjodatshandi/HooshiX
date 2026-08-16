package com.sajtech.notification.infrastructure.security.fingerprint;

import com.sajtech.notification.application.submit.model.FingerprintDigest;
import com.sajtech.notification.application.submit.port.out.IntentFingerprintPort;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.notification.infrastructure.security.keyring.KeyRingMaterial;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;

public final class FileBackedHmacIntentFingerprint implements IntentFingerprintPort {
  public static final String VERSION = "fingerprint-v1";

  private final FileBackedKeyRing keyRing;

  public FileBackedHmacIntentFingerprint(FileBackedKeyRing keyRing) {
    this.keyRing = keyRing;
  }

  @Override
  public FingerprintDigest compute(byte[] canonicalMaterial) {
    KeyRingMaterial active = keyRing.activeKey();
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(active.key());
      return new FingerprintDigest(VERSION, active.keyId(), hmac.doFinal(canonicalMaterial));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
    }
  }

  @Override
  public boolean constantTimeEquals(byte[] left, byte[] right) {
    return left != null && right != null && MessageDigest.isEqual(left, right);
  }
}
