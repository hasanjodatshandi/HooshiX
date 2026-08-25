package com.sajtech.identity.infrastructure.security.challenge;

import com.sajtech.identity.application.password.model.GeneratedRecoveryProof;
import com.sajtech.identity.application.password.port.out.PasswordRecoverySecretPort;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.KeyRingMaterial;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

public final class HmacPasswordRecoverySecret implements PasswordRecoverySecretPort {
  private static final byte[] DOMAIN =
      "hooshix:identity:password-recovery:v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final Pattern CODE = Pattern.compile("^[0-9]{8}$");
  private final FileBackedKeyRing keys;
  private final SecureRandom random = new SecureRandom();

  public HmacPasswordRecoverySecret(FileBackedKeyRing keys) {
    this.keys = keys;
  }

  @Override
  public GeneratedRecoveryProof generate(UUID challengeId) {
    String code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
    KeyRingMaterial key = keys.activeKey();
    return new GeneratedRecoveryProof(code, mac(key.key(), challengeId, code), key.keyId());
  }

  @Override
  public boolean matches(UUID challengeId, String code, byte[] storedVerifier, String keyId) {
    if (code == null
        || !CODE.matcher(code).matches()
        || storedVerifier == null
        || storedVerifier.length != 32) return false;
    byte[] computed = mac(keys.key(keyId), challengeId, code);
    try {
      return MessageDigest.isEqual(computed, storedVerifier);
    } finally {
      Arrays.fill(computed, (byte) 0);
    }
  }

  private static byte[] mac(SecretKey key, UUID challengeId, String code) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      mac.update(DOMAIN);
      mac.update(challengeId.toString().getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) 0);
      return mac.doFinal(code.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Password recovery HMAC is unavailable", exception);
    }
  }
}
