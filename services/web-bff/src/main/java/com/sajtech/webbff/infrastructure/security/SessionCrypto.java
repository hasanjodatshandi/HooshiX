package com.sajtech.webbff.infrastructure.security;

import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class SessionCrypto {
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final SecureRandom RANDOM = new SecureRandom();
  private final FileBackedKeyRing locatorKeys;
  private final FileBackedKeyRing csrfKeys;
  private final FileBackedKeyRing encryptionKeys;

  public SessionCrypto(
      FileBackedKeyRing locatorKeys, FileBackedKeyRing csrfKeys, FileBackedKeyRing encryptionKeys) {
    this.locatorKeys = Objects.requireNonNull(locatorKeys);
    this.csrfKeys = Objects.requireNonNull(csrfKeys);
    this.encryptionKeys = Objects.requireNonNull(encryptionKeys);
  }

  public IssuedOpaque issueSessionToken() {
    byte[] raw = random(32);
    try {
      var key = locatorKeys.activeKey();
      String token = B64.encodeToString(raw);
      return new IssuedOpaque(key.keyId() + "." + token, locator(key.keyId(), token));
    } finally {
      Arrays.fill(raw, (byte) 0);
    }
  }

  public String locatorFromCookie(String cookie) {
    if (cookie == null || cookie.length() > 128)
      throw new IllegalArgumentException("Session cookie is invalid");
    int dot = cookie.indexOf('.');
    if (dot < 1 || dot != cookie.lastIndexOf('.') || dot == cookie.length() - 1)
      throw new IllegalArgumentException("Session cookie is invalid");
    String keyId = cookie.substring(0, dot), token = cookie.substring(dot + 1);
    if (!keyId.matches("[A-Za-z0-9._-]{1,64}") || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new IllegalArgumentException("Session cookie is invalid");
    return locator(keyId, token);
  }

  public java.util.Set<String> locatorKeyIds() {
    return locatorKeys.keyIds();
  }

  public String userSessionIndex(java.util.UUID userId) {
    if (userId == null) throw new IllegalArgumentException("User ID is missing");
    var key = locatorKeys.activeKey();
    return "web-bff:user-sessions:v1:"
        + key.keyId()
        + ":"
        + hex(hmac(key.key(), "hooshix:web-bff:user-session-index:v1", userId.toString()));
  }

  public String userSessionIndex(String keyId, java.util.UUID userId) {
    if (userId == null) throw new IllegalArgumentException("User ID is missing");
    return "web-bff:user-sessions:v1:"
        + keyId
        + ":"
        + hex(
            hmac(
                locatorKeys.key(keyId),
                "hooshix:web-bff:user-session-index:v1",
                userId.toString()));
  }

  public IssuedCsrf issueCsrf() {
    byte[] raw = random(32);
    try {
      String clear = B64.encodeToString(raw);
      var key = csrfKeys.activeKey();
      return new IssuedCsrf(
          clear, key.keyId(), hex(hmac(key.key(), "hooshix:web-bff:csrf:v1", clear)));
    } finally {
      Arrays.fill(raw, (byte) 0);
    }
  }

  public boolean csrfMatches(String clear, String keyId, String expectedHex) {
    if (clear == null
        || !clear.matches("[A-Za-z0-9_-]{43}")
        || keyId == null
        || expectedHex == null
        || !expectedHex.matches("[0-9a-f]{64}")) return false;
    try {
      byte[] expected = HexFormat.of().parseHex(expectedHex);
      byte[] actual = hmac(csrfKeys.key(keyId), "hooshix:web-bff:csrf:v1", clear);
      try {
        return MessageDigest.isEqual(expected, actual);
      } finally {
        Arrays.fill(expected, (byte) 0);
        Arrays.fill(actual, (byte) 0);
      }
    } catch (RuntimeException e) {
      return false;
    }
  }

  public EncryptedValue encryptRefresh(String locator, String refresh) {
    if (locator == null || refresh == null || refresh.isBlank() || refresh.length() > 1024)
      throw new IllegalArgumentException("Refresh credential is invalid");
    var key = encryptionKeys.activeKey();
    byte[] nonce = random(12);
    byte[] plaintext = refresh.getBytes(StandardCharsets.UTF_8);
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key.key(), new GCMParameterSpec(128, nonce));
      c.updateAAD(aad(locator, key.keyId()));
      byte[] encrypted = c.doFinal(plaintext);
      return new EncryptedValue(
          key.keyId(), B64.encodeToString(nonce), B64.encodeToString(encrypted));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Refresh encryption is unavailable", e);
    } finally {
      Arrays.fill(nonce, (byte) 0);
      Arrays.fill(plaintext, (byte) 0);
    }
  }

  public EncryptedValue encryptMfaChallenge(String locator, String challenge) {
    if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("MFA challenge is invalid");
    }
    return encrypt(locator, challenge, "mfa-challenge");
  }

  public String decryptRefresh(String locator, EncryptedValue value) {
    if (locator == null || value == null)
      throw new IllegalArgumentException("Encrypted refresh value is missing");
    byte[] nonce = B64(value.nonce());
    byte[] ciphertext = B64(value.ciphertext());
    try {
      if (nonce.length != 12 || ciphertext.length < 16 || ciphertext.length > 2048)
        throw new IllegalArgumentException("Encrypted refresh value is invalid");
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE, encryptionKeys.key(value.keyId()), new GCMParameterSpec(128, nonce));
      c.updateAAD(aad(locator, value.keyId()));
      byte[] clear = c.doFinal(ciphertext);
      try {
        return new String(clear, StandardCharsets.UTF_8);
      } finally {
        Arrays.fill(clear, (byte) 0);
      }
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Refresh decryption is unavailable", e);
    } finally {
      Arrays.fill(nonce, (byte) 0);
      Arrays.fill(ciphertext, (byte) 0);
    }
  }

  public String decryptMfaChallenge(String locator, EncryptedValue value) {
    String challenge = decrypt(locator, value, "mfa-challenge");
    if (!challenge.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalStateException("MFA challenge is malformed");
    }
    return challenge;
  }

  private EncryptedValue encrypt(String locator, String value, String purpose) {
    if (locator == null || value == null || value.isBlank() || value.length() > 1024) {
      throw new IllegalArgumentException("Encrypted session value is invalid");
    }
    var key = encryptionKeys.activeKey();
    byte[] nonce = random(12);
    byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key.key(), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(locator, key.keyId(), purpose));
      byte[] encrypted = cipher.doFinal(plaintext);
      return new EncryptedValue(
          key.keyId(), B64.encodeToString(nonce), B64.encodeToString(encrypted));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Session encryption is unavailable", exception);
    } finally {
      Arrays.fill(nonce, (byte) 0);
      Arrays.fill(plaintext, (byte) 0);
    }
  }

  private String decrypt(String locator, EncryptedValue value, String purpose) {
    if (locator == null || value == null) {
      throw new IllegalArgumentException("Encrypted session value is missing");
    }
    byte[] nonce = B64(value.nonce());
    byte[] ciphertext = B64(value.ciphertext());
    try {
      if (nonce.length != 12 || ciphertext.length < 16 || ciphertext.length > 2048) {
        throw new IllegalArgumentException("Encrypted session value is invalid");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, encryptionKeys.key(value.keyId()), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(locator, value.keyId(), purpose));
      byte[] clear = cipher.doFinal(ciphertext);
      try {
        return new String(clear, StandardCharsets.UTF_8);
      } finally {
        Arrays.fill(clear, (byte) 0);
      }
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalStateException("Session decryption is unavailable", exception);
    } finally {
      Arrays.fill(nonce, (byte) 0);
      Arrays.fill(ciphertext, (byte) 0);
    }
  }

  private String locator(String keyId, String token) {
    return "web-bff:session:v1:"
        + keyId
        + ":"
        + hex(hmac(locatorKeys.key(keyId), "hooshix:web-bff:session-locator:v1", token));
  }

  private static byte[] aad(String locator, String keyId) {
    return ("hooshix:web-bff:refresh:v1\0" + keyId + "\0" + locator)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] aad(String locator, String keyId, String purpose) {
    return ("hooshix:web-bff:" + purpose + ":v1\0" + keyId + "\0" + locator)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] hmac(javax.crypto.SecretKey key, String purpose, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      put(mac, purpose);
      put(mac, value);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Session HMAC is unavailable", e);
    }
  }

  private static void put(Mac mac, String value) {
    byte[] b = value.getBytes(StandardCharsets.UTF_8);
    mac.update(ByteBuffer.allocate(4).putInt(b.length).array());
    mac.update(b);
  }

  private static byte[] random(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  private static byte[] B64(String v) {
    try {
      return Base64.getUrlDecoder().decode(v);
    } catch (IllegalArgumentException e) {
      return new byte[0];
    }
  }

  private static String hex(byte[] b) {
    return HexFormat.of().formatHex(b);
  }

  public record IssuedOpaque(String cookieValue, String locator) {}

  public record IssuedCsrf(String clear, String keyId, String digestHex) {}

  public record EncryptedValue(String keyId, String nonce, String ciphertext) {}
}
