package com.sajtech.identity.infrastructure.security.mfa;

import com.sajtech.identity.application.mfa.model.*;
import com.sajtech.identity.application.mfa.port.out.MfaCryptographyPort;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.KeyRingMaterial;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class JcaMfaCryptography implements MfaCryptographyPort {
  private static final byte[] TOTP_DOMAIN =
      "hooshix:identity:mfa-totp-secret:v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] CHALLENGE_DOMAIN =
      "hooshix:identity:mfa-challenge:v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] RECOVERY_DOMAIN =
      "hooshix:identity:mfa-recovery:v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
  private static final String TOTP_VERSION = "mfa-aes-gcm-v1";
  private static final String CHALLENGE_VERSION = "mfa-challenge-hmac-v1";
  private static final String RECOVERY_VERSION = "mfa-recovery-hmac-v1";
  private static final int MAX_RETAINED_KEYS = 8;
  private final FileBackedKeyRing encryptionKeys;
  private final FileBackedKeyRing digestKeys;
  private final SecureRandom random = new SecureRandom();

  public JcaMfaCryptography(FileBackedKeyRing encryptionKeys, FileBackedKeyRing digestKeys) {
    this.encryptionKeys = encryptionKeys;
    this.digestKeys = digestKeys;
  }

  @Override
  public GeneratedTotpSecret generateTotpSecret(UUID userId, UUID enrollmentId) {
    requireIdentifiers(userId, enrollmentId);
    byte[] secret = new byte[32];
    byte[] nonce = new byte[12];
    random.nextBytes(secret);
    random.nextBytes(nonce);
    KeyRingMaterial key = encryptionKeys.activeKey();
    try {
      String base32 = base32(secret);
      String label = url("SajTech:" + userId);
      String uri =
          "otpauth://totp/"
              + label
              + "?secret="
              + base32
              + "&issuer=SajTech&algorithm=SHA256&digits=6&period=30";
      byte[] ciphertext =
          crypt(Cipher.ENCRYPT_MODE, key.key(), userId, enrollmentId, nonce, secret);
      return new GeneratedTotpSecret(
          base32, uri, new EncryptedTotpSecret(key.keyId(), nonce, ciphertext));
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  @Override
  public OptionalLong verifyTotp(
      UUID userId, UUID enrollmentId, EncryptedTotpSecret encrypted, String code, Instant now) {
    if (userId == null
        || enrollmentId == null
        || encrypted == null
        || code == null
        || !code.matches("[0-9]{6}")
        || now == null) return OptionalLong.empty();
    byte[] secret =
        crypt(
            Cipher.DECRYPT_MODE,
            encryptionKeys.key(encrypted.keyId()),
            userId,
            enrollmentId,
            encrypted.nonce(),
            encrypted.ciphertext());
    try {
      long current = Math.floorDiv(now.getEpochSecond(), 30);
      for (long step = current - 1; step <= current + 1; step++) {
        byte[] expected = totp(secret, step).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = code.getBytes(StandardCharsets.US_ASCII);
        try {
          if (MessageDigest.isEqual(expected, supplied)) return OptionalLong.of(step);
        } finally {
          Arrays.fill(expected, (byte) 0);
          Arrays.fill(supplied, (byte) 0);
        }
      }
      return OptionalLong.empty();
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  @Override
  public GeneratedMfaChallenge generateChallenge() {
    byte[] clear = new byte[32];
    random.nextBytes(clear);
    try {
      String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(clear);
      KeyRingMaterial key = digestKeys.activeKey();
      return new GeneratedMfaChallenge(
          encoded,
          new MfaDigest(mac(key.key(), CHALLENGE_DOMAIN, encoded), key.keyId(), CHALLENGE_VERSION));
    } finally {
      Arrays.fill(clear, (byte) 0);
    }
  }

  @Override
  public List<MfaDigest> challengeDigestCandidates(String encoded) {
    if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{43}")) return List.of();
    return digestCandidates(CHALLENGE_DOMAIN, CHALLENGE_VERSION, null, encoded);
  }

  @Override
  public List<GeneratedRecoveryCode> generateRecoveryCodes(UUID enrollmentId) {
    if (enrollmentId == null) throw new IllegalArgumentException("Enrollment ID is required");
    KeyRingMaterial key = digestKeys.activeKey();
    List<GeneratedRecoveryCode> result = new ArrayList<>(10);
    for (int index = 0; index < 10; index++) {
      byte[] clear = new byte[10];
      random.nextBytes(clear);
      try {
        String compact = base32(clear);
        String encoded = compact.replaceAll("(.{4})(?!$)", "$1-");
        result.add(
            new GeneratedRecoveryCode(
                encoded,
                new MfaDigest(
                    mac(key.key(), RECOVERY_DOMAIN, enrollmentId, encoded),
                    key.keyId(),
                    RECOVERY_VERSION)));
      } finally {
        Arrays.fill(clear, (byte) 0);
      }
    }
    return List.copyOf(result);
  }

  @Override
  public List<MfaDigest> recoveryDigestCandidates(UUID enrollmentId, String encoded) {
    if (enrollmentId == null || encoded == null || !encoded.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}"))
      return List.of();
    return digestCandidates(RECOVERY_DOMAIN, RECOVERY_VERSION, enrollmentId, encoded);
  }

  private List<MfaDigest> digestCandidates(
      byte[] domain, String version, UUID binding, String encoded) {
    List<KeyRingMaterial> keys = digestKeys.allKeys();
    if (keys.isEmpty() || keys.size() > MAX_RETAINED_KEYS) {
      throw new IllegalStateException("MFA verification key set is invalid");
    }
    return keys.stream()
        .map(
            key ->
                new MfaDigest(
                    binding == null
                        ? mac(key.key(), domain, encoded)
                        : mac(key.key(), domain, binding, encoded),
                    key.keyId(),
                    version))
        .toList();
  }

  private static byte[] crypt(
      int mode, SecretKey key, UUID userId, UUID enrollmentId, byte[] nonce, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(TOTP_DOMAIN);
      cipher.updateAAD(userId.toString().getBytes(StandardCharsets.US_ASCII));
      cipher.updateAAD(new byte[] {0});
      cipher.updateAAD(enrollmentId.toString().getBytes(StandardCharsets.US_ASCII));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("MFA TOTP cryptographic operation failed", exception);
    }
  }

  private static String totp(byte[] secret, long timestep) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
      byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(timestep).array());
      try {
        int offset = digest[digest.length - 1] & 0x0f;
        int binary =
            ((digest[offset] & 0x7f) << 24)
                | ((digest[offset + 1] & 0xff) << 16)
                | ((digest[offset + 2] & 0xff) << 8)
                | (digest[offset + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
      } finally {
        Arrays.fill(digest, (byte) 0);
      }
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("MFA TOTP verification is unavailable", exception);
    }
  }

  private static byte[] mac(SecretKey key, byte[] domain, String value) {
    return mac(key, domain, null, value);
  }

  private static byte[] mac(SecretKey key, byte[] domain, UUID binding, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      mac.update(domain);
      if (binding != null) {
        mac.update(binding.toString().getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) 0);
      }
      return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("MFA digest operation failed", exception);
    }
  }

  private static String base32(byte[] input) {
    StringBuilder output = new StringBuilder((input.length * 8 + 4) / 5);
    int buffer = 0;
    int bits = 0;
    for (byte value : input) {
      buffer = (buffer << 8) | (value & 0xff);
      bits += 8;
      while (bits >= 5) {
        output.append(BASE32[(buffer >> (bits - 5)) & 31]);
        bits -= 5;
      }
    }
    if (bits > 0) output.append(BASE32[(buffer << (5 - bits)) & 31]);
    return output.toString();
  }

  private static String url(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static void requireIdentifiers(UUID userId, UUID enrollmentId) {
    if (userId == null || enrollmentId == null) {
      throw new IllegalArgumentException("MFA identifiers are required");
    }
  }
}
