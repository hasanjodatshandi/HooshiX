package com.sajtech.identity.infrastructure.security.escrow;

import com.sajtech.identity.application.registration.model.DecryptedHandoff;
import com.sajtech.identity.application.registration.model.EncryptedHandoff;
import com.sajtech.identity.application.registration.port.out.NotificationEscrowPort;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.KeyRingMaterial;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmNotificationEscrow implements NotificationEscrowPort {
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int MAX_CIPHERTEXT_BYTES = 8192;
  private static final byte[] DOMAIN =
      "hooshix:identity:notification-handoff:v1\0".getBytes(StandardCharsets.US_ASCII);
  private final FileBackedKeyRing keys;
  private final SecureRandom random;

  public AesGcmNotificationEscrow(FileBackedKeyRing keys) {
    this.keys = keys;
    this.random = new SecureRandom();
  }

  @Override
  public EncryptedHandoff encrypt(
      UUID outboxId, CanonicalContact contact, RegistrationLocale locale, String code) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    KeyRingMaterial key = keys.activeKey();
    byte[] plaintext = encode(contact, locale, code);
    try {
      return new EncryptedHandoff(
          key.keyId(), nonce, crypt(Cipher.ENCRYPT_MODE, key.key(), outboxId, nonce, plaintext));
    } finally {
      java.util.Arrays.fill(plaintext, (byte) 0);
    }
  }

  @Override
  public DecryptedHandoff decrypt(UUID outboxId, String keyId, byte[] nonce, byte[] ciphertext) {
    if (nonce == null
        || nonce.length != NONCE_BYTES
        || ciphertext == null
        || ciphertext.length < 16
        || ciphertext.length > MAX_CIPHERTEXT_BYTES)
      throw new IllegalStateException("Identity handoff escrow is malformed");
    byte[] plaintext = crypt(Cipher.DECRYPT_MODE, keys.key(keyId), outboxId, nonce, ciphertext);
    try {
      return decode(plaintext);
    } finally {
      java.util.Arrays.fill(plaintext, (byte) 0);
    }
  }

  private static byte[] crypt(int mode, SecretKey key, UUID outboxId, byte[] nonce, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(DOMAIN);
      cipher.updateAAD(outboxId.toString().getBytes(StandardCharsets.US_ASCII));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(
          "Identity handoff escrow cryptographic operation failed", exception);
    }
  }

  private static byte[] encode(CanonicalContact contact, RegistrationLocale locale, String code) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(1);
      out.writeUTF(contact.channel().name());
      out.writeUTF(contact.deliveryValue());
      out.writeUTF(locale.name());
      out.writeUTF(code);
      out.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Unable to encode Identity handoff", impossible);
    }
  }

  private static DecryptedHandoff decode(byte[] value) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(value));
      if (in.readInt() != 1)
        throw new IllegalStateException("Identity handoff format is unsupported");
      RegistrationChannel channel = RegistrationChannel.valueOf(in.readUTF());
      String recipient = in.readUTF();
      RegistrationLocale locale = RegistrationLocale.valueOf(in.readUTF());
      String code = in.readUTF();
      if (in.available() != 0 || recipient.isBlank() || !code.matches("[0-9]{8}"))
        throw new IllegalStateException("Identity handoff payload is malformed");
      return new DecryptedHandoff(channel, recipient, locale, code);
    } catch (IOException | IllegalArgumentException exception) {
      throw new IllegalStateException("Identity handoff payload is malformed", exception);
    }
  }
}
