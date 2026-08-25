package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class BrowserAuthControllerTest {
  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

  @Test
  void tenantAuthenticatedIdentityLoginRotatesDirectlyIntoTenantBrowserSession() {
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    IdentityGateway identity = mock(IdentityGateway.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    BrowserAuthController controller =
        new BrowserAuthController(sessions, identity, addresses, Clock.fixed(NOW, ZoneOffset.UTC));
    BrowserSession preauth =
        new BrowserSession(
            "old",
            BrowserSessionMode.PREAUTH,
            null,
            null,
            null,
            null,
            null,
            null,
            "k1",
            "0".repeat(64),
            NOW,
            NOW,
            NOW.plusSeconds(600),
            NOW.plusSeconds(600));
    UUID user = UUID.randomUUID(),
        family = UUID.randomUUID(),
        tenant = UUID.randomUUID(),
        membership = UUID.randomUUID(),
        requestId = UUID.randomUUID();
    byte[] client = {(byte) 192, 0, 2, 10};
    when(addresses.parse("192.0.2.10")).thenReturn(client);
    when(identity.login(
            eq(requestId), eq("EMAIL"), eq("person@example.com"), eq("password"), same(client)))
        .thenReturn(
            new IdentityGateway.LoginResult(
                user,
                "s".repeat(43),
                family,
                "r".repeat(43),
                NOW.plus(Duration.ofDays(7)),
                NOW.plus(Duration.ofDays(30)),
                IdentityGateway.SessionMode.TENANT_AUTHENTICATED,
                tenant,
                membership));
    BrowserSession active =
        new BrowserSession(
            "new",
            BrowserSessionMode.TENANT_AUTHENTICATED,
            user,
            "s".repeat(43),
            family,
            "r".repeat(43),
            tenant,
            membership,
            "k2",
            "1".repeat(64),
            NOW,
            NOW,
            NOW.plus(Duration.ofDays(7)),
            NOW.plus(Duration.ofDays(30)));
    when(sessions.rotateAuthenticatedTenant(
            eq(preauth),
            eq(user),
            eq("s".repeat(43)),
            eq(family),
            eq("r".repeat(43)),
            any(),
            any(),
            eq(tenant),
            eq(membership)))
        .thenReturn(new BrowserSessionGrant("cookie", "csrf", active));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, preauth);
    MockHttpServletResponse response = new MockHttpServletResponse();

    BrowserAuthController.SessionResponse result =
        controller.login(
            requestId.toString(),
            "192.0.2.10",
            new BrowserAuthController.LocalLoginRequest("EMAIL", "person@example.com", "password"),
            request,
            response);

    assertThat(result.mode()).isEqualTo("TENANT_AUTHENTICATED");
    assertThat(result.csrfToken()).isEqualTo("csrf");
    assertThat(response.getHeader("Set-Cookie"))
        .contains("__Host-sajtech-session=cookie")
        .contains("Secure")
        .contains("HttpOnly");
    verify(sessions, never()).rotateAuthenticated(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void mfaRequiredLoginRotatesToFiveMinutePreauthWithoutIdentityCredentials() {
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    IdentityGateway identity = mock(IdentityGateway.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    BrowserAuthController controller =
        new BrowserAuthController(sessions, identity, addresses, Clock.fixed(NOW, ZoneOffset.UTC));
    BrowserSession preauth = preauth();
    UUID userId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    byte[] client = {(byte) 192, 0, 2, 10};
    String challenge = "M".repeat(43);
    when(addresses.parse("192.0.2.10")).thenReturn(client);
    when(identity.login(any(), any(), any(), any(), same(client)))
        .thenReturn(
            new IdentityGateway.LoginResult(
                userId,
                null,
                null,
                null,
                null,
                null,
                IdentityGateway.SessionMode.MFA_REQUIRED,
                null,
                null,
                challenge));
    BrowserSession mfaSession =
        new BrowserSession(
            "new",
            BrowserSessionMode.MFA_PREAUTH,
            userId,
            null,
            null,
            null,
            null,
            null,
            "k2",
            "1".repeat(64),
            NOW,
            NOW,
            NOW.plusSeconds(300),
            NOW.plusSeconds(300),
            challenge);
    when(sessions.rotateMfaPreauth(preauth, userId, challenge, NOW.plusSeconds(300)))
        .thenReturn(new BrowserSessionGrant("cookie", "csrf", mfaSession));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, preauth);

    var result =
        controller.login(
            requestId.toString(),
            "192.0.2.10",
            new BrowserAuthController.LocalLoginRequest("EMAIL", "person@example.com", "password"),
            request,
            new MockHttpServletResponse());

    assertThat(result.mode()).isEqualTo("MFA_PREAUTH");
    verify(sessions, never()).rotateAuthenticated(any(), any(), any(), any(), any(), any(), any());
    verify(sessions, never())
        .rotateAuthenticatedTenant(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void mfaCompletionUsesServerSessionChallengeAndRotatesToAuthenticatedSession() {
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    IdentityGateway identity = mock(IdentityGateway.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    BrowserAuthController controller =
        new BrowserAuthController(sessions, identity, addresses, Clock.fixed(NOW, ZoneOffset.UTC));
    UUID userId = UUID.randomUUID();
    UUID familyId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    String challenge = "M".repeat(43);
    byte[] client = {(byte) 192, 0, 2, 10};
    BrowserSession mfaSession =
        new BrowserSession(
            "mfa",
            BrowserSessionMode.MFA_PREAUTH,
            userId,
            null,
            null,
            null,
            null,
            null,
            "k1",
            "0".repeat(64),
            NOW,
            NOW,
            NOW.plusSeconds(300),
            NOW.plusSeconds(300),
            challenge);
    var identityResult =
        new IdentityGateway.LoginResult(
            userId,
            "s".repeat(43),
            familyId,
            "r".repeat(43),
            NOW.plus(Duration.ofDays(7)),
            NOW.plus(Duration.ofDays(30)),
            IdentityGateway.SessionMode.AUTHENTICATED_ONBOARDING,
            null,
            null);
    BrowserSession authenticated =
        new BrowserSession(
            "active",
            BrowserSessionMode.AUTHENTICATED_ONBOARDING,
            userId,
            "s".repeat(43),
            familyId,
            "r".repeat(43),
            null,
            null,
            "k2",
            "1".repeat(64),
            NOW,
            NOW,
            NOW.plus(Duration.ofDays(7)),
            NOW.plus(Duration.ofDays(30)));
    when(addresses.parse("192.0.2.10")).thenReturn(client);
    when(identity.completeMfaAuthentication(
            eq(requestId),
            eq(challenge),
            eq(new IdentityGateway.MfaProof("TOTP", "123456")),
            same(client)))
        .thenReturn(identityResult);
    when(sessions.rotateAuthenticated(
            mfaSession,
            userId,
            "s".repeat(43),
            familyId,
            "r".repeat(43),
            identityResult.idleExpiresAt(),
            identityResult.absoluteExpiresAt()))
        .thenReturn(new BrowserSessionGrant("cookie", "csrf", authenticated));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, mfaSession);

    var result =
        controller.completeMfa(
            requestId.toString(),
            "192.0.2.10",
            new BrowserAuthController.MfaProofRequest("TOTP", "123456"),
            request,
            new MockHttpServletResponse());

    assertThat(result.mode()).isEqualTo("AUTHENTICATED_ONBOARDING");
    verify(identity)
        .completeMfaAuthentication(
            requestId, challenge, new IdentityGateway.MfaProof("TOTP", "123456"), client);
  }

  @Test
  void csrfRecoveryAfterMfaPageReloadRotatesWithoutExposingOrReplacingChallenge() {
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    IdentityGateway identity = mock(IdentityGateway.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    BrowserAuthController controller =
        new BrowserAuthController(sessions, identity, addresses, Clock.fixed(NOW, ZoneOffset.UTC));
    UUID userId = UUID.randomUUID();
    String challenge = "M".repeat(43);
    BrowserSession old =
        new BrowserSession(
            "old",
            BrowserSessionMode.MFA_PREAUTH,
            userId,
            null,
            null,
            null,
            null,
            null,
            "k1",
            "0".repeat(64),
            NOW,
            NOW,
            NOW.plusSeconds(300),
            NOW.plusSeconds(300),
            challenge);
    BrowserSession rotated =
        new BrowserSession(
            "next",
            BrowserSessionMode.MFA_PREAUTH,
            userId,
            null,
            null,
            null,
            null,
            null,
            "k2",
            "1".repeat(64),
            NOW,
            NOW,
            NOW.plusSeconds(300),
            NOW.plusSeconds(300),
            challenge);
    when(sessions.rotateMfaPreauth(old, userId, challenge, old.absoluteExpiresAt()))
        .thenReturn(new BrowserSessionGrant("cookie", "new-csrf", rotated));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, old);

    var result = controller.rotateCsrf(request, new MockHttpServletResponse());

    assertThat(result)
        .isEqualTo(new BrowserAuthController.SessionResponse("new-csrf", "MFA_PREAUTH"));
    verifyNoInteractions(identity);
    verify(sessions).rotateMfaPreauth(old, userId, challenge, old.absoluteExpiresAt());
  }

  private static BrowserSession preauth() {
    return new BrowserSession(
        "old",
        BrowserSessionMode.PREAUTH,
        null,
        null,
        null,
        null,
        null,
        null,
        "k1",
        "0".repeat(64),
        NOW,
        NOW,
        NOW.plusSeconds(600),
        NOW.plusSeconds(600));
  }
}
