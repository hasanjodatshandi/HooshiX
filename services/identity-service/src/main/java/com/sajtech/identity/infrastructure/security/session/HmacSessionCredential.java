package com.sajtech.identity.infrastructure.security.session;

import com.sajtech.identity.application.authentication.model.GeneratedRefreshCredential;
import com.sajtech.identity.application.authentication.model.RefreshDigest;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.KeyRingMaterial;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;

public final class HmacSessionCredential implements SessionCredentialPort {
  private static final int SECRET_BYTES = 32;
  private static final int ENCODED_LENGTH = 43;
  private static final String VERSION = "refresh-hmac-v1";
  private static final byte[] DOMAIN =
      "hooshix:identity:refresh:v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private final SecureRandom random = new SecureRandom();
  private final FileBackedKeyRing keys;

  public HmacSessionCredential(FileBackedKeyRing keys) {
    this.keys = keys;
  }

  @Override
  public String newSessionId() {
    byte[] secret = randomBytes();
    try {
      return ENCODER.encodeToString(secret);
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  @Override
  public GeneratedRefreshCredential newRefreshCredential() {
    byte[] secret = randomBytes();
    try {
      String encoded = ENCODER.encodeToString(secret);
      KeyRingMaterial active = keys.activeKey();
      return new GeneratedRefreshCredential(
          encoded, new RefreshDigest(active.keyId(), VERSION, digest(active, secret)));
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  @Override
  public List<RefreshDigest> digestCandidates(String encodedCredential) {
    byte[] secret = decodeCanonical(encodedCredential);
    try {
      List<RefreshDigest> result = new ArrayList<>();
      for (KeyRingMaterial material : keys.allKeys()) {
        result.add(new RefreshDigest(material.keyId(), VERSION, digest(material, secret)));
      }
      return List.copyOf(result);
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  private byte[] randomBytes() {
    byte[] value = new byte[SECRET_BYTES];
    random.nextBytes(value);
    return value;
  }

  private static byte[] decodeCanonical(String encoded) {
    if (encoded == null || encoded.length() != ENCODED_LENGTH || encoded.indexOf('=') >= 0) {
      throw new IllegalArgumentException("Refresh credential is invalid");
    }
    byte[] decoded;
    try {
      decoded = DECODER.decode(encoded);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Refresh credential is invalid", exception);
    }
    if (decoded.length != SECRET_BYTES || !ENCODER.encodeToString(decoded).equals(encoded)) {
      Arrays.fill(decoded, (byte) 0);
      throw new IllegalArgumentException("Refresh credential is invalid");
    }
    return decoded;
  }

  private static byte[] digest(KeyRingMaterial material, byte[] secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(material.key());
      mac.update(DOMAIN);
      mac.update(secret);
      return mac.doFinal();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Refresh credential HMAC is unavailable", exception);
    }
  }
}
