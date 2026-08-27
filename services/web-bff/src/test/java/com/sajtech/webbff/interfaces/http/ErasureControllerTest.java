package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sajtech.webbff.application.model.BrowserSecurityContext;
import com.sajtech.webbff.application.model.BrowserSession;
import com.sajtech.webbff.application.model.BrowserSessionMode;
import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ErasureControllerTest {
  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

  @Test
  void acceptedErasureForwardsOnlyServerHeldCredentialAndImmediatelyErasesBrowserSessions() {
    IdentityGateway identity = mock(IdentityGateway.class);
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    BrowserSession session = authenticated();
    UUID requestId = UUID.randomUUID();
    UUID erasureRequestId = UUID.randomUUID();
    IdentityGateway.MfaProof proof = new IdentityGateway.MfaProof("TOTP", "123456");
    when(identity.requestSelfErasure(
            requestId, session.refreshCredential(), "ERASE_MY_ACCOUNT", proof))
        .thenReturn(new IdentityGateway.ErasureRequest(erasureRequestId, "REQUESTED", "1"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, session);
    MockHttpServletResponse response = new MockHttpServletResponse();

    var result =
        new ErasureController(identity, sessions)
            .request(
                requestId.toString(),
                new ErasureController.ErasureBody(
                    "ERASE_MY_ACCOUNT", new ErasureController.Proof("TOTP", "123456")),
                request,
                response);

    assertThat(result.getStatusCode().value()).isEqualTo(202);
    assertThat(result.getBody())
        .isEqualTo(
            new ErasureController.ErasureAccepted(erasureRequestId.toString(), "REQUESTED", "1"));
    verify(sessions).eraseUser(session.userId());
    assertThat(response.getHeader("Set-Cookie"))
        .contains("Max-Age=0", "Secure", "HttpOnly", "SameSite=Lax");
  }

  @Test
  void proofTypeMustMatchItsCodeShapeAtTheHttpBoundary() {
    assertThat(new ErasureController.Proof("TOTP", "123456").isConsistent()).isTrue();
    assertThat(new ErasureController.Proof("TOTP", "AAAA-BBBB-CCCC-DDDD").isConsistent()).isFalse();
    assertThat(new ErasureController.Proof("RECOVERY_CODE", "AAAA-BBBB-CCCC-DDDD").isConsistent())
        .isTrue();
    assertThat(new ErasureController.Proof("RECOVERY_CODE", "123456").isConsistent()).isFalse();
  }

  private static BrowserSession authenticated() {
    return new BrowserSession(
        "browser-locator",
        BrowserSessionMode.AUTHENTICATED_ONBOARDING,
        UUID.randomUUID(),
        "s".repeat(43),
        UUID.randomUUID(),
        "r".repeat(43),
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
