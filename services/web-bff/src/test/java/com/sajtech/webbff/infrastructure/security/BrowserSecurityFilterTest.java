package com.sajtech.webbff.infrastructure.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.configuration.WebBffProperties;
import com.sajtech.webbff.infrastructure.session.RedisBffSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;

class BrowserSecurityFilterTest {
  private RedisBffSessionRepository sessions;
  private BrowserSecurityFilter filter;

  @BeforeEach
  void setUp() {
    sessions = mock(RedisBffSessionRepository.class);
    filter = new BrowserSecurityFilter(properties(), sessions);
  }

  @Test
  void exactAdrSecurityHeadersAreAppliedToApplicationResponses() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilter(request, response, chain);
    verify(chain).doFilter(any(), same(response));
    assertThat(response.getHeader("Content-Security-Policy"))
        .isEqualTo(
            "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'");
    assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  void unsafeCrossOriginRequestIsRejectedBeforeSessionLookup() throws Exception {
    var request = post("/api/v1/auth/session/bootstrap");
    request.addHeader("Origin", "https://evil.example");
    request.addHeader("Sec-Fetch-Site", "cross-site");
    var response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(403);
    verifyNoInteractions(sessions);
    verifyNoInteractions(chain);
  }

  @Test
  void missingFetchMetadataFailsClosedOnUnsafeBrowserSurface() throws Exception {
    var request = post("/api/v1/auth/session/bootstrap");
    request.addHeader("Origin", "https://app.example.com");
    var response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("fetch-metadata-required");
    verifyNoInteractions(chain);
  }

  @Test
  void csrfMismatchRejectsAuthenticatedMutation() throws Exception {
    BrowserSession session = session();
    when(sessions.load("cookie")).thenReturn(Optional.of(session));
    when(sessions.csrfMatches(session, "wrong")).thenReturn(false);
    var request = post("/api/v1/identity/tenant-selection");
    request.addHeader("Origin", "https://app.example.com");
    request.addHeader("Sec-Fetch-Site", "same-origin");
    request.addHeader("X-CSRF-Token", "wrong");
    request.setCookies(new Cookie(BrowserSecurityFilter.COOKIE, "cookie"));
    var response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(403);
    verify(sessions, never()).touch(any());
    verifyNoInteractions(chain);
  }

  @Test
  void loadedSessionUsesSharedControllerSecurityContextAttribute() throws Exception {
    BrowserSession session = session();
    when(sessions.load("cookie")).thenReturn(Optional.of(session));
    when(sessions.touch(session)).thenReturn(true);
    var request = new MockHttpServletRequest("GET", "/api/v1/identity/profile");
    request.setCookies(new Cookie(BrowserSecurityFilter.COOKIE, "cookie"));
    var response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(request.getAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE)).isSameAs(session);
    verify(chain).doFilter(same(request), same(response));
  }

  @Test
  void failedAtomicTouchInvalidatesCurrentRequest() throws Exception {
    BrowserSession session = session();
    when(sessions.load("cookie")).thenReturn(Optional.of(session));
    when(sessions.csrfMatches(session, "csrf")).thenReturn(true);
    when(sessions.touch(session)).thenReturn(false);
    var request = post("/api/v1/identity/tenant-selection");
    request.addHeader("Origin", "https://app.example.com");
    request.addHeader("Sec-Fetch-Site", "same-origin");
    request.addHeader("X-CSRF-Token", "csrf");
    request.setCookies(new Cookie(BrowserSecurityFilter.COOKIE, "cookie"));
    var response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    verifyNoInteractions(chain);
  }

  private static MockHttpServletRequest post(String path) {
    MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
    r.setContentType("application/json");
    r.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return r;
  }

  private static BrowserSession session() {
    Instant now = Instant.parse("2026-08-19T12:00:00Z");
    return new BrowserSession(
        "locator",
        BrowserSessionMode.TENANT_AUTHENTICATED,
        UUID.randomUUID(),
        "s".repeat(43),
        UUID.randomUUID(),
        "refresh",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "k1",
        "0".repeat(64),
        now,
        now,
        now.plus(Duration.ofDays(7)),
        now.plus(Duration.ofDays(30)));
  }

  private static WebBffProperties properties() {
    return new WebBffProperties(
        true,
        true,
        URI.create("https://app.example.com"),
        "dns:///identity",
        "dns:///authorization",
        "redis://localhost:6379",
        Path.of("locator"),
        Path.of("csrf"),
        Path.of("refresh"),
        Duration.ofMinutes(5),
        Duration.ofHours(1),
        128,
        128,
        Map.of("/api/v1/authorization", "authorization-service"));
  }
}
