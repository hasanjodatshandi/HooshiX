package com.sajtech.webbff.infrastructure.security;

import com.sajtech.webbff.application.model.BrowserSession;
import com.sajtech.webbff.configuration.WebBffProperties;
import com.sajtech.webbff.infrastructure.session.RedisBffSessionRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

public final class BrowserSecurityFilter extends OncePerRequestFilter {
  public static final String SESSION_ATTRIBUTE = BrowserSecurityFilter.class.getName() + ".session";
  public static final String COOKIE = "__Host-sajtech-session";
  private final WebBffProperties properties;
  private final RedisBffSessionRepository sessions;

  public BrowserSecurityFilter(WebBffProperties properties, RedisBffSessionRepository sessions) {
    this.properties = properties;
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    headers(response);
    if (!request.getRequestURI().startsWith("/api/v1/")) {
      chain.doFilter(request, response);
      return;
    }
    if (!properties.runtimeEnabled()) {
      problem(response, 503, "runtime-disabled");
      return;
    }
    int max = request.getRequestURI().startsWith("/api/v1/auth/") ? 64 * 1024 : 256 * 1024;
    String ct = request.getContentType();
    if (ct != null && ct.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
      problem(response, 415, "multipart-not-supported");
      return;
    }
    long len = request.getContentLengthLong();
    if (len > max) {
      problem(response, 413, "payload-too-large");
      return;
    }
    HttpServletRequest effective = request;
    if (hasBody(request.getMethod())) {
      byte[] body = readBounded(request, max);
      if (body == null) {
        problem(response, 413, "payload-too-large");
        return;
      }
      effective = new CachedRequest(request, body);
    }
    boolean unsafe = unsafe(request.getMethod());
    if (unsafe && !origin().equals(request.getHeader("Origin"))) {
      problem(response, 403, "invalid-origin");
      return;
    }
    if (unsafe) {
      String site = request.getHeader("Sec-Fetch-Site");
      if (site == null && properties.requireFetchMetadata()) {
        problem(response, 403, "fetch-metadata-required");
        return;
      }
      if (site != null && !"same-origin".equals(site)) {
        problem(response, 403, "cross-site-request");
        return;
      }
    }
    String cookie = sessionCookie(request);
    BrowserSession session = null;
    if (cookie != null) {
      session = sessions.load(cookie).orElse(null);
      if (session == null) {
        clearCookie(response);
        problem(response, 401, "invalid-session");
        return;
      }
      effective.setAttribute(SESSION_ATTRIBUTE, session);
      if (unsafe && !sessions.csrfMatches(session, request.getHeader("X-CSRF-Token"))) {
        problem(response, 403, "csrf-invalid");
        return;
      }
      if (!sessions.touch(session)) {
        clearCookie(response);
        problem(response, 401, "invalid-session");
        return;
      }
    } else if (unsafe && !"/api/v1/auth/session/bootstrap".equals(request.getRequestURI())) {
      problem(response, 401, "session-required");
      return;
    }
    chain.doFilter(effective, response);
  }

  private static byte[] readBounded(HttpServletRequest r, int max) throws IOException {
    try (InputStream in = r.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 8192))) {
      byte[] buf = new byte[8192];
      int total = 0, n;
      while ((n = in.read(buf)) != -1) {
        total += n;
        if (total > max) return null;
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  private String origin() {
    var u = properties.publicOrigin();
    return u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
  }

  private static boolean unsafe(String m) {
    return !Set.of("GET", "HEAD", "OPTIONS").contains(m);
  }

  private static boolean hasBody(String m) {
    return Set.of("POST", "PUT", "PATCH", "DELETE").contains(m);
  }

  private static String sessionCookie(HttpServletRequest r) {
    Cookie[] cookies = r.getCookies();
    if (cookies == null) return null;
    String found = null;
    for (Cookie c : cookies)
      if (COOKIE.equals(c.getName())) {
        if (found != null) return "!duplicate";
        found = c.getValue();
      }
    return found;
  }

  public static void setCookie(HttpServletResponse r, String value, long maxAgeSeconds) {
    r.addHeader(
        "Set-Cookie",
        COOKIE
            + "="
            + value
            + "; Max-Age="
            + maxAgeSeconds
            + "; Path=/; Secure; HttpOnly; SameSite=Lax");
  }

  public static void clearCookie(HttpServletResponse r) {
    r.addHeader("Set-Cookie", COOKIE + "=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax");
  }

  private static void headers(HttpServletResponse r) {
    r.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'");
    r.setHeader("X-Content-Type-Options", "nosniff");
    r.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    r.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    r.setHeader("Cross-Origin-Resource-Policy", "same-origin");
    r.setHeader("Cross-Origin-Opener-Policy", "same-origin");
    r.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    r.setHeader("Cache-Control", "no-store");
  }

  private static void problem(HttpServletResponse r, int status, String code) throws IOException {
    r.setStatus(status);
    r.setContentType("application/problem+json");
    r.setCharacterEncoding(StandardCharsets.UTF_8.name());
    r.getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Request rejected\",\"status\":"
                + status
                + ",\"code\":\""
                + code
                + "\"}");
  }

  private static final class CachedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        public boolean isFinished() {
          return in.available() == 0;
        }

        public boolean isReady() {
          return true;
        }

        public void setReadListener(ReadListener l) {
          if (l != null)
            try {
              l.onDataAvailable();
              if (isFinished()) l.onAllDataRead();
            } catch (IOException e) {
              l.onError(e);
            }
        }

        public int read() {
          return in.read();
        }

        public int read(byte[] b, int o, int l) {
          return in.read(b, o, l);
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
