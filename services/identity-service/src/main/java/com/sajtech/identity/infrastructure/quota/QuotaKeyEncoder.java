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
    String contactKey =
        contact == null
            ? null
            : key(
                key,
                "contact",
                operation,
                contact.channel().name().getBytes(StandardCharsets.US_ASCII),
                contact.canonicalValue().getBytes(StandardCharsets.UTF_8));
    String exactKey =
        key(key, "client-ip-exact", operation, new byte[] {(byte) exact.length}, exact);
    String aggregateKey =
        key(
            key,
            "client-network-aggregate",
            operation,
            new byte[] {(byte) aggregate.length},
            aggregate);
    return new EncodedKeys(key.keyId(), contactKey, exactKey, aggregateKey);
  }

  private static String key(
      KeyRingMaterial material, String dimension, QuotaOperation operation, byte[]... parts) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(material.key());
      mac.update("hooshix:identity:quota:v1\0".getBytes(StandardCharsets.US_ASCII));
      mac.update(operation.name().getBytes(StandardCharsets.US_ASCII));
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
    if (input == null || (input.length != 4 && input.length != 16))
      throw new IllegalArgumentException("Trusted client address is invalid");
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
}
