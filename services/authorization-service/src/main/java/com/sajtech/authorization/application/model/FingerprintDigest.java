package com.sajtech.authorization.application.model;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public record FingerprintDigest(
    String version, String activeKeyId, Map<String, byte[]> candidateDigests) {
  public FingerprintDigest {
    if (version == null
        || version.isBlank()
        || activeKeyId == null
        || activeKeyId.isBlank()
        || candidateDigests == null
        || candidateDigests.isEmpty()) {
      throw new IllegalArgumentException("Fingerprint digest metadata is invalid");
    }
    Map<String, byte[]> copy = new HashMap<>();
    candidateDigests.forEach(
        (key, value) -> {
          if (key == null || key.isBlank() || value == null || value.length != 32) {
            throw new IllegalArgumentException("Fingerprint digest candidate is invalid");
          }
          copy.put(key, value.clone());
        });
    if (!copy.containsKey(activeKeyId))
      throw new IllegalArgumentException("Active fingerprint digest is missing");
    candidateDigests = Map.copyOf(copy);
  }

  public byte[] activeValue() {
    return candidateDigests.get(activeKeyId).clone();
  }

  public boolean matches(String storedVersion, String storedKeyId, byte[] storedValue) {
    if (!version.equals(storedVersion) || storedKeyId == null || storedValue == null) return false;
    byte[] candidate = candidateDigests.get(storedKeyId);
    return candidate != null && MessageDigest.isEqual(candidate, storedValue);
  }

  @Override
  public Map<String, byte[]> candidateDigests() {
    Map<String, byte[]> copy = new HashMap<>();
    candidateDigests.forEach((key, value) -> copy.put(key, value.clone()));
    return Map.copyOf(copy);
  }
}
