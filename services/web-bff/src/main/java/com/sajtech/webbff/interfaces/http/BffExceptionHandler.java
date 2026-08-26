package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.BrowserSecurityContext;
import com.sajtech.webbff.application.model.BrowserSession;
import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import jakarta.servlet.http.*;
import java.net.URI;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public final class BffExceptionHandler {
  private final BrowserSessionPort sessions;

  public BffExceptionHandler(BrowserSessionPort sessions) {
    this.sessions = sessions;
  }

  @ExceptionHandler(BffException.class)
  ResponseEntity<Map<String, Object>> bff(
      BffException e, HttpServletRequest request, HttpServletResponse response) {
    if (e.error() == BffError.AUTHENTICATION_FAILED) {
      Object value = request.getAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE);
      if (value instanceof BrowserSession session) sessions.destroy(session);
      HttpSupport.clearCookie(response);
    }
    int status =
        switch (e.error()) {
          case INVALID_SESSION, AUTHENTICATION_FAILED, OIDC_INVALID_RESPONSE, OIDC_STATE_INVALID ->
              401;
          case INVALID_ORIGIN, FETCH_METADATA_REQUIRED, CSRF_INVALID, AUTHORIZATION_DENIED -> 403;
          case RATE_LIMITED -> 429;
          case DEPENDENCY_UNAVAILABLE,
              RUNTIME_DISABLED,
              OIDC_UNAVAILABLE,
              QUOTA_TIME_SOURCE_UNHEALTHY,
              QUOTA_CAPACITY_UNHEALTHY ->
              503;
          case TENANT_SELECTION_REQUIRED,
              REGISTRATION_REJECTED,
              PASSWORD_REJECTED,
              ACCOUNT_LINK_REQUIRED,
              EXTERNAL_IDENTITY_REJECTED ->
              409;
          case INVALID_REQUEST -> 400;
        };
    return problem(status, e.error().name(), request.getRequestURI());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, Object>> notFound(
      NoResourceFoundException e, HttpServletRequest request) {
    return problem(404, "NOT_FOUND", request.getRequestURI());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MissingRequestHeaderException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<Map<String, Object>> invalid(Exception e, HttpServletRequest request) {
    return problem(400, "INVALID_REQUEST", request.getRequestURI());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String, Object>> unsupportedMedia(
      HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
    return problem(415, "UNSUPPORTED_MEDIA_TYPE", request.getRequestURI());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
    return problem(500, "INTERNAL_ERROR", request.getRequestURI());
  }

  private static ResponseEntity<Map<String, Object>> problem(
      int status, String code, String instance) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    headers.setCacheControl(CacheControl.noStore());
    return new ResponseEntity<>(
        Map.of(
            "type",
            URI.create(
                    "urn:hooshix:problem:"
                        + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
                .toString(),
            "title",
            title(status),
            "status",
            status,
            "code",
            code,
            "instance",
            instance),
        headers,
        HttpStatusCode.valueOf(status));
  }

  private static String title(int status) {
    return switch (status) {
      case 400 -> "Invalid request";
      case 401 -> "Authentication required";
      case 403 -> "Request forbidden";
      case 404 -> "Resource not found";
      case 409 -> "Request precondition failed";
      case 429 -> "Request rate limited";
      case 503 -> "Dependency unavailable";
      default -> "Request failed";
    };
  }
}
