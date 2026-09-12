package com.sajtech.conversation.infrastructure.security.content;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.conversation.infrastructure.security.keyring.FileBackedContentKeyRing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.*;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AesGcmContentCryptoTest {
  @TempDir Path directory;
  private AesGcmContentCrypto crypto;
  private UUID tenant;
  private UUID conversation;
  private UUID content;

  @BeforeEach
  void setUp() throws Exception {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) 7);
    Path file = directory.resolve("content.properties");
    Files.writeString(
        file, "active_key_id=k1\nkey.k1=" + Base64.getEncoder().encodeToString(key) + "\n");
    var ring = new FileBackedContentKeyRing(file, Clock.systemUTC(), Duration.ofMinutes(2));
    crypto = new AesGcmContentCrypto(ring, new SecureRandom());
    tenant = UUID.randomUUID();
    conversation = UUID.randomUUID();
    content = UUID.randomUUID();
  }

  @Test
  void roundTripsWithoutPersistingPlaintext() {
    String plaintext = "متن محرمانه گفتگو";
    EncryptedContent encrypted =
        crypto.encrypt(tenant, conversation, content, ContentPurpose.USER_MESSAGE, plaintext);

    assertThat(encrypted.keyId()).isEqualTo("k1");
    assertThat(encrypted.nonce()).hasSize(12);
    assertThat(new String(encrypted.ciphertext(), StandardCharsets.UTF_8))
        .doesNotContain(plaintext);
    assertThat(
            crypto.decrypt(tenant, conversation, content, ContentPurpose.USER_MESSAGE, encrypted))
        .isEqualTo(plaintext);
  }

  @Test
  void rejectsTenantPurposeAndCiphertextTampering() {
    EncryptedContent encrypted =
        crypto.encrypt(tenant, conversation, content, ContentPurpose.TITLE, "private title");

    assertThatThrownBy(
            () ->
                crypto.decrypt(
                    UUID.randomUUID(), conversation, content, ContentPurpose.TITLE, encrypted))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                crypto.decrypt(
                    tenant, conversation, content, ContentPurpose.ASSISTANT_MESSAGE, encrypted))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] tampered = encrypted.ciphertext();
    tampered[0] ^= 1;
    EncryptedContent changed = new EncryptedContent(encrypted.keyId(), encrypted.nonce(), tampered);
    assertThatThrownBy(
            () -> crypto.decrypt(tenant, conversation, content, ContentPurpose.TITLE, changed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEmptyPlaintextAndInvalidEnvelope() {
    assertThatThrownBy(
            () -> crypto.encrypt(tenant, conversation, content, ContentPurpose.TITLE, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EncryptedContent("bad/id", new byte[12], new byte[16]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
