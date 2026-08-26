package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.*;
import com.sajtech.webbff.configuration.WebBffProperties;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;

class OidcControllerTest {
  private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");
  private final OidcPreauthPort preauth = mock(OidcPreauthPort.class);
  private final GoogleOidcProvider provider = mock(GoogleOidcProvider.class);
  private final IdentityGateway identity = mock(IdentityGateway.class);
  private final BrowserSessionPort sessions = mock(BrowserSessionPort.class);
  private final TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
  private final OidcQuotaPort quota = mock(OidcQuotaPort.class);
  private final WebBffProperties properties = mock(WebBffProperties.class);
  private final OidcController controller =
      new OidcController(
          preauth,
          provider,
          identity,
          sessions,
          addresses,
          quota,
          properties,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void configure() {
    when(properties.googleOidcEnabled()).thenReturn(true);
    when(properties.publicOrigin()).thenReturn(URI.create("https://app.example.test"));
    when(addresses.parse("203.0.113.10")).thenReturn(new byte[] {(byte) 203, 0, 113, 10});
  }

  @Test
  void loginStartCreatesBoundPreauthAndReturnsOnlyProviderAuthorizationUrl() {
    BrowserSession old = session(BrowserSessionMode.PREAUTH);
    MockHttpServletRequest request = request(old);
    MockHttpServletResponse response = new MockHttpServletResponse();
    OidcAuthorizationStart started =
        new OidcAuthorizationStart(
            "p".repeat(43),
            "s".repeat(43),
            "n".repeat(43),
            "v".repeat(43),
            "c".repeat(43),
            NOW.plusSeconds(600));
    when(preauth.begin(
            isNull(),
            eq(OidcPurpose.LOGIN),
            eq(old.locator()),
            eq("https://app.example.test/api/v1/auth/oidc/google/callback"),
            eq("/welcome")))
        .thenReturn(started);
    when(provider.authorizationUri(any(), any(), any(), any()))
        .thenReturn(URI.create("https://accounts.google.com/o/oauth2/v2/auth?state=opaque"));

    OidcController.OidcStartResponse result =
        controller.startLogin(
            "203.0.113.10", new OidcController.OidcStartRequest("/welcome"), request, response);

    assertThat(result.authorizationUrl()).startsWith("https://accounts.google.com/");
    assertThat(response.getHeader("Set-Cookie"))
        .startsWith("__Host-sajtech-preauth=")
        .contains("Secure", "HttpOnly", "SameSite=Lax");
    verify(quota)
        .consume(
            eq(OidcQuotaPort.Operation.OIDC_START),
            argThat(value -> Arrays.equals(value, new byte[] {(byte) 203, 0, 113, 10})));
  }

  @Test
  void callbackConsumesStateThenRotatesOnboardingSessionWithoutProviderTokenExposure() {
    BrowserSession old = session(BrowserSessionMode.PREAUTH);
    MockHttpServletRequest request = request(old);
    request.setCookies(new Cookie(BrowserSecurityContext.OIDC_PREAUTH_COOKIE_NAME, "p".repeat(43)));
    request.addParameter("state", "s".repeat(43));
    request.addParameter("code", "provider-code");
    MockHttpServletResponse response = new MockHttpServletResponse();
    OidcPreauthTransaction transaction =
        new OidcPreauthTransaction(
            OidcPurpose.LOGIN,
            old.locator(),
            "n".repeat(43),
            "v".repeat(43),
            "https://app.example.test/api/v1/auth/oidc/google/callback",
            "/welcome",
            NOW.minusSeconds(5),
            NOW.plusSeconds(595));
    VerifiedGoogleIdentity verified =
        new VerifiedGoogleIdentity(
            "https://accounts.google.com",
            "google-subject",
            "person@example.com",
            true,
            "Google",
            "Person");
    UUID userId = UUID.randomUUID(), family = UUID.randomUUID();
    IdentityGateway.LoginResult identityResult =
        new IdentityGateway.LoginResult(
            userId,
            "i".repeat(43),
            family,
            "r".repeat(43),
            NOW.plus(Duration.ofDays(7)),
            NOW.plus(Duration.ofDays(30)),
            IdentityGateway.SessionMode.AUTHENTICATED_ONBOARDING,
            null,
            null,
            null);
    BrowserSession rotated = session(BrowserSessionMode.AUTHENTICATED_ONBOARDING);
    when(preauth.consume("p".repeat(43), "s".repeat(43))).thenReturn(Optional.of(transaction));
    when(provider.exchangeAndValidate(
            "provider-code", "v".repeat(43), "n".repeat(43), transaction.redirectUri()))
        .thenReturn(verified);
    when(identity.establishExternalIdentity(any(), any(), eq(NOW), eq(verified), any()))
        .thenReturn(identityResult);
    when(sessions.rotateAuthenticated(
            eq(old), eq(userId), eq("i".repeat(43)), eq(family), eq("r".repeat(43)), any(), any()))
        .thenReturn(new BrowserSessionGrant("new-cookie", "new-csrf", rotated));

    var result = controller.callback("203.0.113.10", request, response);

    assertThat(result.getStatusCode().value()).isEqualTo(303);
    assertThat(result.getHeaders().getLocation()).isEqualTo(URI.create("/welcome"));
    assertThat(response.getHeaders("Set-Cookie"))
        .anyMatch(value -> value.startsWith("__Host-sajtech-session=new-cookie"))
        .anyMatch(value -> value.startsWith("__Host-sajtech-preauth=;"));
    verify(identity)
        .establishExternalIdentity(
            any(), argThat(value -> value.length == 32), eq(NOW), eq(verified), any());
    verify(quota).consume(eq(OidcQuotaPort.Operation.OIDC_CALLBACK), any());
  }

  @Test
  void missingOrConsumedStateFailsBeforeProviderAndClearsPreauthCookie() {
    BrowserSession old = session(BrowserSessionMode.PREAUTH);
    MockHttpServletRequest request = request(old);
    request.setCookies(new Cookie(BrowserSecurityContext.OIDC_PREAUTH_COOKIE_NAME, "p".repeat(43)));
    request.addParameter("state", "s".repeat(43));
    request.addParameter("code", "provider-code");
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(preauth.consume(any(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.callback("203.0.113.10", request, response))
        .isInstanceOfSatisfying(
            BffException.class,
            exception -> assertThat(exception.error()).isEqualTo(BffError.OIDC_STATE_INVALID));

    verifyNoInteractions(provider, identity);
    assertThat(response.getHeaders("Set-Cookie"))
        .anyMatch(value -> value.startsWith("__Host-sajtech-preauth=;"));
  }

  @Test
  void malformedStateFailsClosedAndClearsPreauthCookie() {
    MockHttpServletRequest request = request(session(BrowserSessionMode.PREAUTH));
    request.setCookies(new Cookie(BrowserSecurityContext.OIDC_PREAUTH_COOKIE_NAME, "p".repeat(43)));
    request.addParameter("state", "first", "second");
    request.addParameter("code", "provider-code");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThatThrownBy(() -> controller.callback("203.0.113.10", request, response))
        .isInstanceOfSatisfying(
            BffException.class,
            exception -> assertThat(exception.error()).isEqualTo(BffError.OIDC_STATE_INVALID));

    verifyNoInteractions(preauth, provider, identity);
    assertThat(response.getHeaders("Set-Cookie"))
        .anyMatch(value -> value.startsWith("__Host-sajtech-preauth=;"));
  }

  @TestFactory
  Collection<DynamicTest> returnTargetRejectsNormalizationAndOpenRedirectShapes() {
    return List.of(
            "https://evil.example/",
            "//evil.example/path",
            "/safe?next=evil",
            "/safe#fragment",
            "/safe\\evil",
            "/a/../admin",
            "/%2f%2fevil.example")
        .stream()
        .map(
            value ->
                DynamicTest.dynamicTest(
                    value,
                    () ->
                        assertThatThrownBy(() -> OidcController.validateReturnTarget(value))
                            .isInstanceOf(BffException.class)))
        .toList();
  }

  private static MockHttpServletRequest request(BrowserSession session) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, session);
    return request;
  }

  private static BrowserSession session(BrowserSessionMode mode) {
    Instant idle = NOW.plusSeconds(600);
    boolean authenticated = mode == BrowserSessionMode.AUTHENTICATED_ONBOARDING;
    return new BrowserSession(
        "web-bff:session:v1:k1:" + "a".repeat(64),
        mode,
        authenticated ? UUID.randomUUID() : null,
        authenticated ? "i".repeat(43) : null,
        authenticated ? UUID.randomUUID() : null,
        authenticated ? "r".repeat(43) : null,
        null,
        null,
        "k1",
        "d".repeat(64),
        NOW,
        NOW,
        idle,
        NOW.plusSeconds(600));
  }
}
