package com.sajtech.identity.infrastructure.security.fingerprint;

import com.sajtech.identity.application.registration.model.CommandDedupRecord;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.KeyRingMaterial;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

public final class HmacIntentFingerprint implements IntentFingerprintPort {
  public static final String VERSION = "identity-fingerprint-v1";
  private static final byte[] DOMAIN =
      "hooshix:identity:intent-fingerprint:v1\0".getBytes(StandardCharsets.US_ASCII);
  private final FileBackedKeyRing keys;

  public HmacIntentFingerprint(FileBackedKeyRing keys) {
    this.keys = keys;
  }

  @Override
  public FingerprintDigest digest(byte[] material) {
    KeyRingMaterial key = keys.activeKey();
    return new FingerprintDigest(mac(key.key(), material), VERSION, key.keyId());
  }

  @Override
  public boolean matches(byte[] material, CommandDedupRecord stored) {
    if (!VERSION.equals(stored.fingerprintVersion())) return false;
    byte[] computed = mac(keys.key(stored.fingerprintKeyId()), material);
    try {
      return MessageDigest.isEqual(computed, stored.fingerprint());
    } finally {
      java.util.Arrays.fill(computed, (byte) 0);
    }
  }

  private static byte[] mac(SecretKey key, byte[] material) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      mac.update(DOMAIN);
      return mac.doFinal(material);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Identity fingerprint HMAC is unavailable", exception);
    }
  }
}
