package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class PasswordControllerTest {
  private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

  @Test
  void passwordChangeUsesOnlyServerHeldRefreshAndRotatesBrowserSecurityState() {
    IdentityGateway identity = mock(IdentityGateway.class);
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    BrowserSession old = authenticatedSession("server-held-refresh", "old-locator");
    Instant idle = NOW.plus(Duration.ofDays(7));
    Instant absolute = NOW.plus(Duration.ofDays(30));
    when(identity.changePassword(
            any(UUID.class), eq("server-held-refresh"), eq("current"), eq("new password value")))
        .thenReturn(new IdentityGateway.PasswordChangeResult("rotated-refresh", idle, absolute));
    BrowserSession rotated = authenticatedSession("rotated-refresh", "new-locator");
    when(sessions.rotateSecurityState(old, "rotated-refresh", idle, absolute))
        .thenReturn(new BrowserSessionGrant("new-cookie", "new-csrf", rotated));
    var request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, old);
    var response = new MockHttpServletResponse();

    var result =
        new PasswordController(identity, sessions, addresses, Clock.fixed(NOW, ZoneOffset.UTC))
            .change(
                UUID.randomUUID().toString(),
                new PasswordController.Change("current", "new password value"),
                request,
                response);

    assertThat(result.getBody())
        .isEqualTo(new PasswordController.PasswordChanged(true, "new-csrf"));
    assertThat(response.getHeader("Set-Cookie"))
        .contains("new-cookie", "Secure", "HttpOnly", "SameSite=Lax");
    verify(sessions).rotateSecurityState(old, "rotated-refresh", idle, absolute);
  }

  @Test
  void recoveryRequestUsesOnlyEdgeParsedClientAddress() {
    IdentityGateway identity = mock(IdentityGateway.class);
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    byte[] exactAddress = new byte[] {(byte) 203, 0, 113, 9};
    when(addresses.parse("203.0.113.9")).thenReturn(exactAddress);
    when(identity.requestPasswordRecovery(
            any(), eq("EMAIL"), eq("person@example.com"), same(exactAddress)))
        .thenReturn(true);

    var accepted =
        new PasswordController(identity, sessions, addresses, Clock.fixed(NOW, ZoneOffset.UTC))
            .request(
                UUID.randomUUID().toString(),
                "203.0.113.9",
                new PasswordController.RecoveryRequest("EMAIL", "person@example.com"));

    assertThat(accepted.accepted()).isTrue();
    verify(addresses).parse("203.0.113.9");
  }

  private static BrowserSession authenticatedSession(String refresh, String locator) {
    return new BrowserSession(
        locator,
        BrowserSessionMode.AUTHENTICATED_ONBOARDING,
        UUID.randomUUID(),
        "identity-session",
        UUID.randomUUID(),
        refresh,
        null,
        null,
        "csrf-k1",
        "0".repeat(64),
        NOW,
        NOW,
        NOW.plus(Duration.ofDays(7)),
        NOW.plus(Duration.ofDays(30)));
  }
}
