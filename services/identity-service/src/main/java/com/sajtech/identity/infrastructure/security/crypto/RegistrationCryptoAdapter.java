package com.sajtech.identity.infrastructure.security.crypto;

import com.sajtech.identity.application.registration.model.ChallengeVerifier;
import com.sajtech.identity.application.registration.model.EscrowCiphertext;
import com.sajtech.identity.application.registration.model.RequestFingerprint;
import com.sajtech.identity.application.registration.model.RequestPurpose;
import com.sajtech.identity.application.registration.port.out.RegistrationCryptoPort;
import com.sajtech.identity.infrastructure.security.keyring.FileKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.KeyRingSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class RegistrationCryptoAdapter implements RegistrationCryptoPort {
  private static final short FINGERPRINT_VERSION = 1;
  private static final int GCM_TAG_BITS = 128;
  private static final int GCM_NONCE_BYTES = 12;

  private final FileKeyRing hmacKeys;
  private final FileKeyRing escrowKeys;
  private final SecureRandom secureRandom;

  public RegistrationCryptoAdapter(
      FileKeyRing hmacKeys, FileKeyRing escrowKeys, SecureRandom secureRandom) {
    this.hmacKeys = Objects.requireNonNull(hmacKeys, "hmacKeys");
    this.escrowKeys = Objects.requireNonNull(escrowKeys, "escrowKeys");
    this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
  }

  @Override
  public RequestFingerprint fingerprint(RequestPurpose purpose, byte[] canonicalIntent) {
    KeyRingSnapshot snapshot = hmacKeys.snapshot();
    return new RequestFingerprint(
        FINGERPRINT_VERSION,
        snapshot.activeKeyId(),
        hmac(snapshot.activeKey(), "identity-idempotency-v1:" + purpose.name(), canonicalIntent));
  }

  @Override
  public boolean verifyFingerprint(
      RequestPurpose purpose, byte[] canonicalIntent, RequestFingerprint storedFingerprint) {
    if (storedFingerprint.version() != FINGERPRINT_VERSION) {
      return false;
    }
    byte[] key;
    try {
      key = hmacKeys.snapshot().key(storedFingerprint.keyId());
    } catch (IllegalArgumentException exception) {
      return false;
    }
    byte[] candidate =
        hmac(key, "identity-idempotency-v1:" + purpose.name(), canonicalIntent);
    try {
      return MessageDigest.isEqual(candidate, storedFingerprint.digest());
    } finally {
      Arrays.fill(candidate, (byte) 0);
      Arrays.fill(key, (byte) 0);
    }
  }

  @Override
  public String newVerificationCode() {
    int value = secureRandom.nextInt(100_000_000);
    return String.format(java.util.Locale.ROOT, "%08d", value);
  }

  @Override
  public ChallengeVerifier challengeVerifier(String code) {
    KeyRingSnapshot snapshot = hmacKeys.snapshot();
    return new ChallengeVerifier(
        snapshot.activeKeyId(),
        hmac(
            snapshot.activeKey(),
            "identity-registration-challenge-v1",
            code.getBytes(StandardCharsets.US_ASCII)));
  }

  @Override
  public boolean matchesChallenge(String code, ChallengeVerifier verifier) {
    byte[] key;
    try {
      key = hmacKeys.snapshot().key(verifier.keyId());
    } catch (IllegalArgumentException exception) {
      return false;
    }
    byte[] candidate =
        hmac(
            key,
            "identity-registration-challenge-v1",
            code.getBytes(StandardCharsets.US_ASCII));
    try {
      return MessageDigest.isEqual(candidate, verifier.digest());
    } finally {
      Arrays.fill(candidate, (byte) 0);
      Arrays.fill(key, (byte) 0);
    }
  }

  @Override
  public EscrowCiphertext encryptCallerEscrow(UUID outboxId, byte[] plaintext) {
    KeyRingSnapshot snapshot = escrowKeys.snapshot();
    byte[] nonce = new byte[GCM_NONCE_BYTES];
    secureRandom.nextBytes(nonce);
    byte[] key = snapshot.activeKey();
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, nonce));
      cipher.updateAAD(aad(outboxId));
      return new EscrowCiphertext(snapshot.activeKeyId(), nonce, cipher.doFinal(plaintext));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("caller escrow encryption failed", exception);
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  @Override
  public byte[] decryptCallerEscrow(UUID outboxId, EscrowCiphertext ciphertext) {
    byte[] key = escrowKeys.snapshot().key(ciphertext.keyId());
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, ciphertext.nonce()));
      cipher.updateAAD(aad(outboxId));
      return cipher.doFinal(ciphertext.ciphertext());
    } catch (GeneralSecurityException exception) {
      throw new IllegalArgumentException("caller escrow decryption failed", exception);
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  private static byte[] hmac(byte[] key, String domain, byte[] value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
      mac.update((byte) (domainBytes.length >>> 8));
      mac.update((byte) domainBytes.length);
      mac.update(domainBytes);
      mac.update((byte) (value.length >>> 24));
      mac.update((byte) (value.length >>> 16));
      mac.update((byte) (value.length >>> 8));
      mac.update((byte) value.length);
      return mac.doFinal(value);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC unavailable", exception);
    }
  }

  private static byte[] aad(UUID outboxId) {
    return ("identity-caller-escrow-v1:" + outboxId).getBytes(StandardCharsets.US_ASCII);
  }
}
