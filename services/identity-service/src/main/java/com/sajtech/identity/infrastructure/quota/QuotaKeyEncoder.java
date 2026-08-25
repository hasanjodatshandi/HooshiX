package com.sajtech.identity.infrastructure.quota;

import com.sajtech.identity.application.registration.model.QuotaOperation;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.KeyRingMaterial;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;

public final class QuotaKeyEncoder {
  private static final HexFormat HEX = HexFormat.of();
  private final FileBackedKeyRing keys;

  public QuotaKeyEncoder(FileBackedKeyRing keys) {
    this.keys = keys;
  }

  public EncodedKeys encode(QuotaOperation operation, CanonicalContact contact, byte[] address) {
    byte[] exact = normalizeAddress(address);
    byte[] aggregate = aggregate(exact);
    KeyRingMaterial key = keys.activeKey();
    String operationName = operation.name();
    String contactKey = contact == null ? null : contactKey(key, operationName, contact);
    String exactKey =
        key(key, "client-ip-exact", operationName, new byte[] {(byte) exact.length}, exact);
    String aggregateKey =
        key(
            key,
            "client-network-aggregate",
            operationName,
            new byte[] {(byte) aggregate.length},
            aggregate);
    return new EncodedKeys(key.keyId(), contactKey, exactKey, aggregateKey);
  }

  public LoginSourceKeys encodeLoginSource(byte[] address) {
    byte[] exact = normalizeAddress(address);
    byte[] aggregate = aggregate(exact);
    KeyRingMaterial material = keys.activeKey();
    return new LoginSourceKeys(
        key(material, "client-ip-exact", "LOGIN", new byte[] {(byte) exact.length}, exact),
        key(
            material,
            "client-network-aggregate",
            "LOGIN",
            new byte[] {(byte) aggregate.length},
            aggregate));
  }

  public String encodeLoginSubject(CanonicalContact contact) {
    if (contact == null) throw new IllegalArgumentException("Login contact is required");
    return contactKey(keys.activeKey(), "LOGIN", contact);
  }

  public MfaKeys encodeMfa(String operation, UUID userId, byte[] address) {
    if (operation == null || !operation.matches("[A-Z_]{3,32}") || userId == null) {
      throw new IllegalArgumentException("MFA quota identity is invalid");
    }
    byte[] exact = normalizeAddress(address);
    byte[] aggregate = aggregate(exact);
    KeyRingMaterial material = keys.activeKey();
    return new MfaKeys(
        key(material, "user", operation, userId.toString().getBytes(StandardCharsets.US_ASCII)),
        key(material, "client-ip-exact", operation, new byte[] {(byte) exact.length}, exact),
        key(
            material,
            "client-network-aggregate",
            operation,
            new byte[] {(byte) aggregate.length},
            aggregate));
  }

  public LoginSourceKeys encodeMfaRecoverySource(byte[] address) {
    byte[] exact = normalizeAddress(address);
    byte[] aggregate = aggregate(exact);
    KeyRingMaterial material = keys.activeKey();
    return new LoginSourceKeys(
        key(material, "client-ip-exact", "MFA_RECOVERY", new byte[] {(byte) exact.length}, exact),
        key(
            material,
            "client-network-aggregate",
            "MFA_RECOVERY",
            new byte[] {(byte) aggregate.length},
            aggregate));
  }

  public String encodeMfaRecoverySubject(UUID userId) {
    if (userId == null) throw new IllegalArgumentException("MFA quota user is required");
    return key(
        keys.activeKey(),
        "user",
        "MFA_RECOVERY",
        userId.toString().getBytes(StandardCharsets.US_ASCII));
  }

  private static String contactKey(
      KeyRingMaterial material, String operation, CanonicalContact contact) {
    return key(
        material,
        "contact",
        operation,
        contact.channel().name().getBytes(StandardCharsets.US_ASCII),
        contact.canonicalValue().getBytes(StandardCharsets.UTF_8));
  }

  private static String key(
      KeyRingMaterial material, String dimension, String operation, byte[]... parts) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(material.key());
      mac.update("hooshix:identity:quota:v1\0".getBytes(StandardCharsets.US_ASCII));
      mac.update(operation.getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) 0);
      mac.update(dimension.getBytes(StandardCharsets.US_ASCII));
      for (byte[] part : parts) {
        mac.update(ByteBuffer.allocate(4).putInt(part.length).array());
        mac.update(part);
      }
      return "identity:quota:v1:" + material.keyId() + ":" + HEX.formatHex(mac.doFinal());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Identity quota HMAC is unavailable", exception);
    }
  }

  private static byte[] normalizeAddress(byte[] input) {
    if (input == null || (input.length != 4 && input.length != 16)) {
      throw new IllegalArgumentException("Trusted client address is invalid");
    }
    if (input.length == 16) {
      boolean mapped = true;
      for (int i = 0; i < 10; i++) mapped &= input[i] == 0;
      mapped &= input[10] == (byte) 0xff && input[11] == (byte) 0xff;
      if (mapped) return java.util.Arrays.copyOfRange(input, 12, 16);
    }
    try {
      return InetAddress.getByAddress(input).getAddress();
    } catch (UnknownHostException impossible) {
      throw new IllegalArgumentException("Trusted client address is invalid", impossible);
    }
  }

  private static byte[] aggregate(byte[] exact) {
    byte[] value = exact.clone();
    if (value.length == 4) {
      value[3] = 0;
    } else {
      for (int i = 8; i < 16; i++) value[i] = 0;
    }
    return value;
  }

  public record EncodedKeys(
      String keyId, String contactKey, String exactIpKey, String aggregateNetworkKey) {}

  public record LoginSourceKeys(String exactIpKey, String aggregateNetworkKey) {}

  public record MfaKeys(String userKey, String exactIpKey, String aggregateNetworkKey) {}
}
