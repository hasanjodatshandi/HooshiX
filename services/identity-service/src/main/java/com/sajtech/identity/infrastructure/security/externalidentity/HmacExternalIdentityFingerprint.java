package com.sajtech.identity.infrastructure.security.externalidentity;

import com.sajtech.identity.application.externalidentity.port.out.ExternalIdentityFingerprintPort;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.KeyRingMaterial;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

public final class HmacExternalIdentityFingerprint implements ExternalIdentityFingerprintPort {
  public static final String VERSION = "oidc-evidence-hmac-v1";
  private static final byte[] DOMAIN =
      "hooshix:identity:oidc-evidence:v1\0".getBytes(StandardCharsets.US_ASCII);
  private final FileBackedKeyRing keys;

  public HmacExternalIdentityFingerprint(FileBackedKeyRing keys) {
    this.keys = keys;
  }

  @Override
  public FingerprintDigest digest(byte[] material) {
    KeyRingMaterial key = keys.activeKey();
    return new FingerprintDigest(mac(key.key(), material), VERSION, key.keyId());
  }

  @Override
  public boolean matches(byte[] material, byte[] expected, String keyId, String version) {
    if (!VERSION.equals(version) || expected == null) return false;
    byte[] computed = mac(keys.key(keyId), material);
    try {
      return MessageDigest.isEqual(computed, expected);
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
      throw new IllegalStateException("OIDC evidence fingerprint is unavailable", exception);
    }
  }
}
