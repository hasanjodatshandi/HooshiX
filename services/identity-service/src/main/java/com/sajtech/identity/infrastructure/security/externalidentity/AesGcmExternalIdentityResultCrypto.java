package com.sajtech.identity.infrastructure.security.externalidentity;

import com.sajtech.identity.application.externalidentity.model.EncryptedExternalIdentityResult;
import com.sajtech.identity.application.externalidentity.port.out.ExternalIdentityResultCryptoPort;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.KeyRingMaterial;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmExternalIdentityResultCrypto implements ExternalIdentityResultCryptoPort {
  private static final byte[] DOMAIN =
      "hooshix:identity:oidc-result:v1\0".getBytes(StandardCharsets.US_ASCII);
  private final FileBackedKeyRing keys;
  private final SecureRandom random;

  public AesGcmExternalIdentityResultCrypto(FileBackedKeyRing keys) {
    this(keys, new SecureRandom());
  }

  AesGcmExternalIdentityResultCrypto(FileBackedKeyRing keys, SecureRandom random) {
    this.keys = keys;
    this.random = random;
  }

  @Override
  public EncryptedExternalIdentityResult encrypt(
      byte[] evidenceId, String operation, byte[] clear) {
    KeyRingMaterial key = keys.activeKey();
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    return new EncryptedExternalIdentityResult(
        key.keyId(),
        nonce,
        crypt(Cipher.ENCRYPT_MODE, key, nonce, aad(evidenceId, operation), clear));
  }

  @Override
  public byte[] decrypt(
      byte[] evidenceId, String operation, EncryptedExternalIdentityResult encrypted) {
    KeyRingMaterial key = new KeyRingMaterial(encrypted.keyId(), keys.key(encrypted.keyId()));
    return crypt(
        Cipher.DECRYPT_MODE,
        key,
        encrypted.nonce(),
        aad(evidenceId, operation),
        encrypted.ciphertext());
  }

  private static byte[] crypt(
      int mode, KeyRingMaterial key, byte[] nonce, byte[] aad, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, key.key(), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad);
      return cipher.doFinal(input);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("OIDC evidence result protection is unavailable", exception);
    }
  }

  private static byte[] aad(byte[] evidenceId, String operation) {
    byte[] op = operation.getBytes(StandardCharsets.US_ASCII);
    return ByteBuffer.allocate(DOMAIN.length + evidenceId.length + 1 + op.length)
        .put(DOMAIN)
        .put(evidenceId)
        .put((byte) 0)
        .put(op)
        .array();
  }
}
