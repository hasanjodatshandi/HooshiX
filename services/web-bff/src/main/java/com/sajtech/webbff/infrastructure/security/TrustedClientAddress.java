package com.sajtech.webbff.infrastructure.security;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.TrustedClientAddressPort;
import java.net.*;
import java.util.Locale;
import java.util.regex.Pattern;

public final class TrustedClientAddress implements TrustedClientAddressPort {
  private static final Pattern IPV4 =
      Pattern.compile("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
  private static final String MAPPED_PREFIX = "::ffff:";

  @Override
  public byte[] parse(String value) {
    if (value == null
        || value.length() > 45
        || value.indexOf(',') >= 0
        || value.indexOf('%') >= 0
        || value.indexOf('[') >= 0
        || value.indexOf(']') >= 0
        || value.chars().anyMatch(Character::isWhitespace)) throw invalid();
    if (IPV4.matcher(value).matches()) return ipv4(value);
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith(MAPPED_PREFIX)) {
      String tail = value.substring(MAPPED_PREFIX.length());
      if (!IPV4.matcher(tail).matches()) throw invalid();
      return ipv4(tail);
    }
    try {
      if (value.indexOf(':') < 0 || !IPV6.matcher(value).matches()) throw invalid();
      InetAddress address = InetAddress.getByName(value);
      byte[] raw = address.getAddress();
      if (raw.length != 16) throw invalid();
      return raw;
    } catch (UnknownHostException e) {
      throw invalid();
    }
  }

  private static byte[] ipv4(String value) {
    String[] parts = value.split("\\.");
    byte[] out = new byte[4];
    try {
      for (int i = 0; i < 4; i++) {
        int n = Integer.parseInt(parts[i]);
        if (n > 255) throw invalid();
        out[i] = (byte) n;
      }
      return out;
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  private static BffException invalid() {
    return new BffException(BffError.INVALID_REQUEST, "Trusted client address is invalid");
  }
}
