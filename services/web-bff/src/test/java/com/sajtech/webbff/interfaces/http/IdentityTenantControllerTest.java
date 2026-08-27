package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sajtech.webbff.application.BffError;
import com.sajtech.webbff.application.BffException;
import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdentityTenantControllerTest {
  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

  @Test
  void deletingSelectedTenantRotatesBrowserToOnboardingWithoutExposingRefresh() {
    IdentityGateway identity = mock(IdentityGateway.class);
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    BrowserSession old = tenantSession();
    UUID requestId = UUID.randomUUID();
    when(identity.deleteTenant(requestId, "server-refresh", old.selectedTenantId()))
        .thenReturn(
            new IdentityGateway.TenantLifecycleResult(
                old.selectedTenantId(), "ACTIVE", "DELETED", true));
    BrowserSession onboarding = onboardingSession(old);
    when(sessions.rotateAuthenticated(
            old,
            old.userId(),
            old.identitySessionId(),
            old.refreshFamilyId(),
            old.refreshCredential(),
            old.idleExpiresAt(),
            old.absoluteExpiresAt()))
        .thenReturn(new BrowserSessionGrant("rotated-cookie", "rotated-csrf", onboarding));
    MockHttpServletRequest request = request(old);
    MockHttpServletResponse response = new MockHttpServletResponse();

    var result =
        new IdentityTenantController(identity, sessions, Clock.fixed(NOW, ZoneOffset.UTC))
            .delete(requestId.toString(), old.selectedTenantId().toString(), request, response);

    assertThat(result.targetLifecycle()).isEqualTo("DELETED");
    assertThat(result.pending()).isTrue();
    assertThat(result.csrfToken()).isEqualTo("rotated-csrf");
    assertThat(result.mode()).isEqualTo("AUTHENTICATED_ONBOARDING");
    assertThat(response.getHeader("Set-Cookie")).contains("rotated-cookie", "Secure", "HttpOnly");
    assertThat(result.toString()).doesNotContain("server-refresh");
  }

  @Test
  void deletingDifferentTenantFailsBeforeCallingIdentity() {
    IdentityGateway identity = mock(IdentityGateway.class);
    BrowserSession old = tenantSession();
    IdentityTenantController controller =
        new IdentityTenantController(
            identity, mock(BrowserSessionPort.class), Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                controller.delete(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    request(old),
                    new MockHttpServletResponse()))
        .isInstanceOfSatisfying(
            BffException.class,
            failure -> assertThat(failure.error()).isEqualTo(BffError.AUTHORIZATION_DENIED));
    verifyNoInteractions(identity);
  }

  @Test
  void receivedInvitationListingUsesOnlyServerHeldRefreshCredential() {
    IdentityGateway identity = mock(IdentityGateway.class);
    BrowserSession old = tenantSession();
    UUID invitation = UUID.randomUUID();
    when(identity.receivedInvitations("server-refresh"))
        .thenReturn(
            List.of(
                new IdentityGateway.Invitation(
                    invitation,
                    old.selectedTenantId(),
                    "Sample Tenant",
                    "sample-tenant",
                    "PENDING",
                    NOW.plus(Duration.ofDays(7)))));

    var result =
        new IdentityTenantController(
                identity, mock(BrowserSessionPort.class), Clock.fixed(NOW, ZoneOffset.UTC))
            .receivedInvitations(request(old));

    assertThat(result.invitations())
        .singleElement()
        .satisfies(
            x -> {
              assertThat(x.invitationId()).isEqualTo(invitation);
              assertThat(x.state()).isEqualTo("PENDING");
            });
    verify(identity).receivedInvitations("server-refresh");
  }

  private static MockHttpServletRequest request(BrowserSession session) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, session);
    return request;
  }

  private static BrowserSession tenantSession() {
    return new BrowserSession(
        "locator",
        BrowserSessionMode.TENANT_AUTHENTICATED,
        UUID.randomUUID(),
        "s".repeat(43),
        UUID.randomUUID(),
        "server-refresh",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "k1",
        "0".repeat(64),
        NOW,
        NOW,
        NOW.plus(Duration.ofDays(7)),
        NOW.plus(Duration.ofDays(30)));
  }

  private static BrowserSession onboardingSession(BrowserSession old) {
    return new BrowserSession(
        "next-locator",
        BrowserSessionMode.AUTHENTICATED_ONBOARDING,
        old.userId(),
        old.identitySessionId(),
        old.refreshFamilyId(),
        old.refreshCredential(),
        null,
        null,
        "k1",
        "1".repeat(64),
        NOW,
        NOW,
        old.idleExpiresAt(),
        old.absoluteExpiresAt());
  }
}
