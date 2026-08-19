package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.*;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.UUID;

final class HttpSupport {
  static BrowserSession session(HttpServletRequest r) {
    Object v = r.getAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE);
    if (!(v instanceof BrowserSession s))
      throw new BffException(BffError.INVALID_SESSION, "Browser session is required");
    return s;
  }

  static BrowserSession authenticated(HttpServletRequest r) {
    BrowserSession s = session(r);
    if (!s.authenticated() || s.refreshCredential() == null)
      throw new BffException(BffError.INVALID_SESSION, "Authenticated browser session is required");
    return s;
  }

  static BrowserSession tenant(HttpServletRequest r) {
    BrowserSession s = authenticated(r);
    if (!s.tenantAuthenticated())
      throw new BffException(BffError.TENANT_SELECTION_REQUIRED, "Tenant selection is required");
    return s;
  }

  static UUID requestId(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (id.version() != 4 || !id.toString().equals(value)) throw invalid();
      return id;
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  static UUID id(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw invalid();
      return id;
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  static long maxAge(Instant now, Instant idle) {
    long v = Duration.between(now, idle).toSeconds();
    return Math.max(0, Math.min(v, 7L * 24 * 60 * 60));
  }

  static void setCookie(HttpServletResponse response, String value, long maxAge) {
    response.addHeader(
        "Set-Cookie",
        BrowserSecurityContext.COOKIE_NAME
            + "="
            + value
            + "; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age="
            + maxAge);
  }

  static void clearCookie(HttpServletResponse response) {
    response.addHeader(
        "Set-Cookie",
        BrowserSecurityContext.COOKIE_NAME
            + "=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0");
  }

  private static BffException invalid() {
    return new BffException(BffError.INVALID_REQUEST, "Request identifier is invalid");
  }

  private HttpSupport() {}
}
