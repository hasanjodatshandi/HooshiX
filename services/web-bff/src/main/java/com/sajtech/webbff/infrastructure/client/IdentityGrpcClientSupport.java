package com.sajtech.webbff.infrastructure.client;

import com.google.protobuf.Timestamp;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.IdentityGateway.*;
import io.grpc.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

abstract class IdentityGrpcClientSupport {
  protected final ManagedChannel channel;

  IdentityGrpcClientSupport(ManagedChannel channel) {
    this.channel = channel;
  }

  protected IdentityAuthenticationServiceGrpc.IdentityAuthenticationServiceBlockingStub stub() {
    return IdentityAuthenticationServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  protected IdentityAuthenticationServiceGrpc.IdentityAuthenticationServiceBlockingStub
      tokenStub() {
    return IdentityAuthenticationServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1000, TimeUnit.MILLISECONDS);
  }

  protected IdentityProfileServiceGrpc.IdentityProfileServiceBlockingStub profileStub() {
    return IdentityProfileServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  protected IdentityTenantServiceGrpc.IdentityTenantServiceBlockingStub tenantStub() {
    return IdentityTenantServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  protected IdentityErasureServiceGrpc.IdentityErasureServiceBlockingStub erasureStub() {
    return IdentityErasureServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(2000, TimeUnit.MILLISECONDS);
  }

  protected static BffException mapRegistration(StatusRuntimeException e) {
    return switch (e.getStatus().getCode()) {
      case RESOURCE_EXHAUSTED ->
          "QUOTA_EXCEEDED".equals(e.getStatus().getDescription())
              ? new BffException(BffError.RATE_LIMITED, "Registration request quota exceeded", e)
              : new BffException(
                  BffError.DEPENDENCY_UNAVAILABLE, "Identity registration is unavailable", e);
      case INVALID_ARGUMENT ->
          new BffException(BffError.INVALID_REQUEST, "Registration request is invalid", e);
      case ALREADY_EXISTS, FAILED_PRECONDITION ->
          new BffException(BffError.REGISTRATION_REJECTED, "Registration request was rejected", e);
      default ->
          new BffException(
              BffError.DEPENDENCY_UNAVAILABLE, "Identity registration is unavailable", e);
    };
  }

  protected static BffException mapPassword(StatusRuntimeException e) {
    String description = e.getStatus().getDescription();
    return switch (e.getStatus().getCode()) {
      case UNAUTHENTICATED ->
          "INVALID_SESSION".equals(description)
              ? new BffException(BffError.AUTHENTICATION_FAILED, "Password session is invalid", e)
              : new BffException(BffError.PASSWORD_REJECTED, "Password proof was rejected", e);
      case PERMISSION_DENIED, FAILED_PRECONDITION ->
          new BffException(BffError.PASSWORD_REJECTED, "Password request was rejected", e);
      case RESOURCE_EXHAUSTED ->
          new BffException(BffError.RATE_LIMITED, "Password request quota exceeded", e);
      case INVALID_ARGUMENT ->
          new BffException(BffError.INVALID_REQUEST, "Password request is invalid", e);
      default ->
          new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Password service is unavailable", e);
    };
  }

  protected static BffException mapMfa(StatusRuntimeException e) {
    return switch (e.getStatus().getCode()) {
      case UNAUTHENTICATED ->
          new BffException(BffError.AUTHENTICATION_FAILED, "MFA proof was rejected", e);
      case RESOURCE_EXHAUSTED ->
          new BffException(BffError.RATE_LIMITED, "MFA request quota exceeded", e);
      case FAILED_PRECONDITION, ABORTED ->
          new BffException(BffError.INVALID_REQUEST, "MFA request precondition failed", e);
      case INVALID_ARGUMENT ->
          new BffException(BffError.INVALID_REQUEST, "MFA request is invalid", e);
      default -> new BffException(BffError.DEPENDENCY_UNAVAILABLE, "MFA is unavailable", e);
    };
  }

  protected static BffException mapExternalIdentity(StatusRuntimeException exception) {
    String description = exception.getStatus().getDescription();
    return switch (exception.getStatus().getCode()) {
      case UNAUTHENTICATED ->
          new BffException(
              BffError.OIDC_INVALID_RESPONSE, "External identity proof was rejected", exception);
      case ALREADY_EXISTS ->
          new BffException(
              BffError.EXTERNAL_IDENTITY_REJECTED,
              "External identity replay or conflict",
              exception);
      case FAILED_PRECONDITION ->
          new BffException(
              "ACCOUNT_LINK_REQUIRED".equals(description)
                  ? BffError.ACCOUNT_LINK_REQUIRED
                  : BffError.EXTERNAL_IDENTITY_REJECTED,
              "External identity precondition failed",
              exception);
      case INVALID_ARGUMENT ->
          new BffException(
              BffError.INVALID_REQUEST, "External identity request is invalid", exception);
      default ->
          new BffException(
              BffError.DEPENDENCY_UNAVAILABLE,
              "External identity service is unavailable",
              exception);
    };
  }

  protected static BffException map(StatusRuntimeException e) {
    return switch (e.getStatus().getCode()) {
      case UNAUTHENTICATED ->
          new BffException(BffError.AUTHENTICATION_FAILED, "Authentication failed", e);
      case PERMISSION_DENIED ->
          new BffException(BffError.AUTHORIZATION_DENIED, "Authorization denied", e);
      case RESOURCE_EXHAUSTED ->
          new BffException(BffError.RATE_LIMITED, "Request quota exceeded", e);
      case FAILED_PRECONDITION ->
          new BffException(
              "TENANT_SELECTION_REQUIRED".equals(e.getStatus().getDescription())
                  ? BffError.TENANT_SELECTION_REQUIRED
                  : BffError.INVALID_REQUEST,
              "Request precondition failed",
              e);
      case INVALID_ARGUMENT, ALREADY_EXISTS, NOT_FOUND ->
          new BffException(BffError.INVALID_REQUEST, "Request is invalid", e);
      default -> new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Identity is unavailable", e);
    };
  }

  protected static UUID uuid(String v) {
    try {
      UUID result = UUID.fromString(v);
      if (result.version() != 4 || !result.toString().equals(v)) {
        throw new IllegalArgumentException("UUID is not canonical UUIDv4");
      }
      return result;
    } catch (IllegalArgumentException e) {
      throw new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Identity returned invalid UUID", e);
    }
  }

  protected static UUID optionalUuid(String v) {
    return v == null || v.isBlank() ? null : uuid(v);
  }

  protected static Instant instant(Timestamp t) {
    try {
      return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    } catch (RuntimeException e) {
      throw new BffException(
          BffError.DEPENDENCY_UNAVAILABLE, "Identity returned invalid timestamp", e);
    }
  }

  protected static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  protected static SessionMode mode(AuthenticationSessionMode mode) {
    return switch (mode) {
      case AUTHENTICATION_SESSION_MODE_AUTHENTICATED_ONBOARDING ->
          SessionMode.AUTHENTICATED_ONBOARDING;
      case AUTHENTICATION_SESSION_MODE_TENANT_AUTHENTICATED -> SessionMode.TENANT_AUTHENTICATED;
      case AUTHENTICATION_SESSION_MODE_MFA_REQUIRED -> SessionMode.MFA_REQUIRED;
      default ->
          throw new BffException(
              BffError.DEPENDENCY_UNAVAILABLE, "Identity returned unexpected session mode");
    };
  }
}
