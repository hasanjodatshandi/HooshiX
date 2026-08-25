package com.sajtech.webbff.infrastructure.quota;

import com.sajtech.webbff.application.port.out.OidcQuotaPort.Operation;
import com.sajtech.webbff.infrastructure.security.keyring.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;

public final class OidcQuotaKeyEncoder {
  private static final HexFormat HEX = HexFormat.of();
  private final FileBackedKeyRing keys;

  public OidcQuotaKeyEncoder(FileBackedKeyRing keys) {
    this.keys = keys;
  }

  public EncodedKeys encode(Operation operation, byte[] input) {
    byte[] exact = normalize(input);
    byte[] aggregate = aggregate(exact);
    KeyRingMaterial key = keys.activeKey();
    return new EncodedKeys(
        hmac(key, operation, "client-ip-exact", exact),
        hmac(key, operation, "client-network-aggregate", aggregate));
  }

  private static String hmac(
      KeyRingMaterial material, Operation operation, String dimension, byte[] address) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(material.key());
      mac.update("hooshix:web-bff:oidc-quota:v1\0".getBytes(StandardCharsets.US_ASCII));
      mac.update(operation.name().getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) 0);
      mac.update(dimension.getBytes(StandardCharsets.US_ASCII));
      mac.update(ByteBuffer.allocate(4).putInt(address.length).array());
      mac.update(address);
      return "web-bff:oidc-quota:v1:" + material.keyId() + ":" + HEX.formatHex(mac.doFinal());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("OIDC quota HMAC is unavailable", exception);
    }
  }

  private static byte[] normalize(byte[] input) {
    if (input == null || (input.length != 4 && input.length != 16)) {
      throw new IllegalArgumentException("Trusted client address is invalid");
    }
    if (input.length == 16) {
      boolean mapped = true;
      for (int index = 0; index < 10; index++) mapped &= input[index] == 0;
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
    byte[] result = exact.clone();
    if (result.length == 4) result[3] = 0;
    else for (int index = 8; index < 16; index++) result[index] = 0;
    return result;
  }

  public record EncodedKeys(String exactIpKey, String aggregateNetworkKey) {}
}
