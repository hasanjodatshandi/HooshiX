package com.sajtech.webbff.infrastructure.security;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.webbff.application.BffException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class TrustedClientAddressTest {
  private final TrustedClientAddress parser = new TrustedClientAddress();

  @Test
  void parsesCanonicalIpv4AndIpv6() throws Exception {
    assertThat(parser.parse("192.0.2.44")).containsExactly((byte) 192, 0, 2, 44);
    assertThat(parser.parse("2001:db8::1"))
        .containsExactly(InetAddress.getByName("2001:db8::1").getAddress());
  }

  @Test
  void normalizesIpv4MappedIpv6ToIpv4() {
    assertThat(parser.parse("::ffff:192.0.2.9")).containsExactly((byte) 192, 0, 2, 9);
  }

  @Test
  void rejectsForwardingListsHostnamesCidrsPortsAndZoneIds() {
    for (String value :
        new String[] {
          "192.0.2.1, 198.51.100.1",
          "example.com",
          "192.0.2.0/24",
          "192.0.2.1:443",
          "fe80::1%eth0",
          "[2001:db8::1]"
        }) assertThatThrownBy(() -> parser.parse(value)).isInstanceOf(BffException.class);
  }
}
