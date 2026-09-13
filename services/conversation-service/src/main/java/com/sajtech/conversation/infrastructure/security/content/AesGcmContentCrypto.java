package com.sajtech.conversation.infrastructure.security.content;

import com.sajtech.conversation.infrastructure.security.keyring.FileBackedContentKeyRing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmContentCrypto {
  private static final int TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;
  private static final byte AAD_VERSION = 1;
  private final FileBackedContentKeyRing keyRing;
  private final SecureRandom random;

  public AesGcmContentCrypto(FileBackedContentKeyRing keyRing, SecureRandom random) {
    this.keyRing = Objects.requireNonNull(keyRing);
    this.random = Objects.requireNonNull(random);
  }

  public EncryptedContent encrypt(
      UUID tenantId,
      UUID conversationId,
      UUID contentId,
      ContentPurpose purpose,
      String plaintext) {
    requireContext(tenantId, conversationId, contentId, purpose);
    if (plaintext == null || plaintext.isEmpty()) {
      throw new IllegalArgumentException("Conversation plaintext must not be empty");
    }
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    var active = keyRing.activeKey();
    byte[] clear = plaintext.getBytes(StandardCharsets.UTF_8);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, active.key(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(tenantId, conversationId, contentId, purpose));
      return new EncryptedContent(active.keyId(), nonce, cipher.doFinal(clear));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Conversation content encryption failed", exception);
    } finally {
      java.util.Arrays.fill(clear, (byte) 0);
    }
  }

  public String decrypt(
      UUID tenantId,
      UUID conversationId,
      UUID contentId,
      ContentPurpose purpose,
      EncryptedContent encrypted) {
    requireContext(tenantId, conversationId, contentId, purpose);
    Objects.requireNonNull(encrypted);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          keyRing.key(encrypted.keyId()),
          new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
      cipher.updateAAD(aad(tenantId, conversationId, contentId, purpose));
      byte[] clear = cipher.doFinal(encrypted.ciphertext());
      try {
        return new String(clear, StandardCharsets.UTF_8);
      } finally {
        java.util.Arrays.fill(clear, (byte) 0);
      }
    } catch (AEADBadTagException exception) {
      throw new IllegalArgumentException("Conversation content authentication failed");
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Conversation content decryption failed", exception);
    }
  }

  private static byte[] aad(
      UUID tenantId, UUID conversationId, UUID contentId, ContentPurpose purpose) {
    byte[] purposeBytes = purpose.name().getBytes(StandardCharsets.US_ASCII);
    return ByteBuffer.allocate(1 + 16 + 16 + 16 + 1 + purposeBytes.length)
        .put(AAD_VERSION)
        .putLong(tenantId.getMostSignificantBits())
        .putLong(tenantId.getLeastSignificantBits())
        .putLong(conversationId.getMostSignificantBits())
        .putLong(conversationId.getLeastSignificantBits())
        .putLong(contentId.getMostSignificantBits())
        .putLong(contentId.getLeastSignificantBits())
        .put((byte) purposeBytes.length)
        .put(purposeBytes)
        .array();
  }

  private static void requireContext(
      UUID tenantId, UUID conversationId, UUID contentId, ContentPurpose purpose) {
    Objects.requireNonNull(tenantId);
    Objects.requireNonNull(conversationId);
    Objects.requireNonNull(contentId);
    Objects.requireNonNull(purpose);
  }
}
