package com.sajtech.authorization.infrastructure.security;

import com.sajtech.authorization.application.model.FingerprintDigest;
import com.sajtech.authorization.application.port.out.IntentFingerprint;
import com.sajtech.authorization.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.authorization.infrastructure.security.keyring.KeyRingMaterial;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;

public final class HmacIntentFingerprint implements IntentFingerprint {
  public static final String VERSION = "authorization-intent-fingerprint-v1";
  private final FileBackedKeyRing keys;

  public HmacIntentFingerprint(FileBackedKeyRing keys) {
    this.keys = keys;
  }

  @Override
  public FingerprintDigest fingerprint(String operation, String... parts) {
    if (operation == null || !operation.matches("[A-Z0-9_]{1,64}") || parts == null) {
      throw new IllegalArgumentException("Fingerprint input is invalid");
    }
    KeyRingMaterial active = keys.activeKey();
    Map<String, byte[]> candidates = new HashMap<>();
    for (KeyRingMaterial material : keys.allKeys()) {
      candidates.put(material.keyId(), mac(material, operation, parts));
    }
    return new FingerprintDigest(VERSION, active.keyId(), candidates);
  }

  private static byte[] mac(KeyRingMaterial material, String operation, String[] parts) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(material.key());
      update(mac, "hooshix:authorization:intent:v1");
      update(mac, operation);
      for (String part : parts) {
        if (part == null) throw new IllegalArgumentException("Fingerprint part is missing");
        update(mac, part);
      }
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Authorization intent fingerprint is unavailable", e);
    }
  }

  private static void update(Mac mac, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
    mac.update(bytes);
  }
}
