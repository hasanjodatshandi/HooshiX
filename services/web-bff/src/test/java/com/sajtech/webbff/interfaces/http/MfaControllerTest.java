package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MfaControllerTest {
  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

  @Test
  void proofTypeMustMatchItsCodeShapeAtTheHttpBoundary() {
    assertThat(new MfaController.ProofRequest("TOTP", "123456").isConsistent()).isTrue();
    assertThat(
            new MfaController.ProofRequest("RECOVERY_CODE", "AAAA-BBBB-CCCC-DDDD").isConsistent())
        .isTrue();
    assertThat(new MfaController.ProofRequest("TOTP", "AAAA-BBBB-CCCC-DDDD").isConsistent())
        .isFalse();
    assertThat(new BrowserAuthController.MfaProofRequest("RECOVERY_CODE", "123456").isConsistent())
        .isFalse();
  }

  @Test
  void confirmingEnrollmentReturnsOneTimeCodesAndRotatesBrowserSecurityState() {
    IdentityGateway identity = mock(IdentityGateway.class);
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    BrowserSession old = authenticated("old-refresh", "old");
    byte[] clientAddress = {(byte) 203, 0, 113, 9};
    UUID requestId = UUID.randomUUID();
    List<String> codes = List.of("AAAA-BBBB-CCCC-DDDD", "EEEE-FFFF-GGGG-HHHH");
    IdentityGateway.LoginResult identitySession =
        new IdentityGateway.LoginResult(
            old.userId(),
            old.identitySessionId(),
            old.refreshFamilyId(),
            "next-refresh",
            NOW.plus(Duration.ofDays(7)),
            NOW.plus(Duration.ofDays(30)),
            IdentityGateway.SessionMode.AUTHENTICATED_ONBOARDING,
            null,
            null);
    when(addresses.parse("203.0.113.9")).thenReturn(clientAddress);
    when(identity.confirmTotpEnrollment(
            eq(requestId),
            eq("old-refresh"),
            eq("C".repeat(43)),
            eq("123456"),
            same(clientAddress)))
        .thenReturn(new IdentityGateway.MfaMutation(identitySession, codes));
    BrowserSession rotated = authenticated("next-refresh", "next");
    when(sessions.rotateSecurityState(
            old,
            "next-refresh",
            identitySession.idleExpiresAt(),
            identitySession.absoluteExpiresAt()))
        .thenReturn(new BrowserSessionGrant("new-cookie", "new-csrf", rotated));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, old);
    MockHttpServletResponse response = new MockHttpServletResponse();

    var result =
        new MfaController(identity, sessions, addresses, Clock.fixed(NOW, ZoneOffset.UTC))
            .confirmEnrollment(
                requestId.toString(),
                "203.0.113.9",
                new MfaController.ConfirmEnrollmentRequest("C".repeat(43), "123456"),
                request,
                response);

    assertThat(result.recoveryCodes()).containsExactlyElementsOf(codes);
    assertThat(response.getHeader("Set-Cookie")).contains("new-cookie", "Secure", "HttpOnly");
    verify(identity)
        .confirmTotpEnrollment(requestId, "old-refresh", "C".repeat(43), "123456", clientAddress);
  }

  @Test
  void rotatingCodesForwardsOnlyServerHeldRefreshAndTrustedEdgeAddress() {
    IdentityGateway identity = mock(IdentityGateway.class);
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    BrowserSession old = authenticated("server-refresh", "old");
    byte[] clientAddress = {(byte) 192, 0, 2, 1};
    UUID requestId = UUID.randomUUID();
    IdentityGateway.MfaProof proof =
        new IdentityGateway.MfaProof("RECOVERY_CODE", "AAAA-BBBB-CCCC-DDDD");
    IdentityGateway.LoginResult identitySession =
        new IdentityGateway.LoginResult(
            old.userId(),
            old.identitySessionId(),
            old.refreshFamilyId(),
            "next-refresh",
            NOW.plus(Duration.ofDays(7)),
            NOW.plus(Duration.ofDays(30)),
            IdentityGateway.SessionMode.AUTHENTICATED_ONBOARDING,
            null,
            null);
    when(addresses.parse("192.0.2.1")).thenReturn(clientAddress);
    when(identity.rotateRecoveryCodes(requestId, "server-refresh", proof, clientAddress))
        .thenReturn(
            new IdentityGateway.MfaMutation(identitySession, List.of("AAAA-BBBB-CCCC-DDDD")));
    when(sessions.rotateSecurityState(any(), any(), any(), any()))
        .thenReturn(
            new BrowserSessionGrant("cookie", "csrf", authenticated("next-refresh", "next")));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, old);

    new MfaController(identity, sessions, addresses, Clock.fixed(NOW, ZoneOffset.UTC))
        .rotateRecoveryCodes(
            requestId.toString(),
            "192.0.2.1",
            new MfaController.ProofRequest("RECOVERY_CODE", "AAAA-BBBB-CCCC-DDDD"),
            request,
            new MockHttpServletResponse());

    verify(identity).rotateRecoveryCodes(requestId, "server-refresh", proof, clientAddress);
  }

  private static BrowserSession authenticated(String refresh, String locator) {
    return new BrowserSession(
        locator,
        BrowserSessionMode.AUTHENTICATED_ONBOARDING,
        UUID.randomUUID(),
        "s".repeat(43),
        UUID.randomUUID(),
        refresh,
        null,
        null,
        "k1",
        "0".repeat(64),
        NOW,
        NOW,
        NOW.plus(Duration.ofDays(7)),
        NOW.plus(Duration.ofDays(30)));
  }
}
