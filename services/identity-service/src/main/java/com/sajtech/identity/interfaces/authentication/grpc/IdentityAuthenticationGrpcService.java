package com.sajtech.identity.interfaces.authentication.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.in.*;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;

public final class IdentityAuthenticationGrpcService
    extends IdentityAuthenticationServiceGrpc.IdentityAuthenticationServiceImplBase {
  private final AuthenticateLocal authenticate;
  private final RefreshSession refresh;
  private final LogoutCurrent logoutCurrent;
  private final LogoutAll logoutAll;
  private final IssueAudienceAccessToken issueAccessToken;

  public IdentityAuthenticationGrpcService(
      AuthenticateLocal authenticate,
      RefreshSession refresh,
      LogoutCurrent logoutCurrent,
      LogoutAll logoutAll,
      IssueAudienceAccessToken issueAccessToken) {
    this.authenticate = authenticate;
    this.refresh = refresh;
    this.logoutCurrent = logoutCurrent;
    this.logoutAll = logoutAll;
    this.issueAccessToken = issueAccessToken;
  }

  @Override
  public void authenticateLocal(
      AuthenticateLocalRequest request, StreamObserver<AuthenticateLocalResponse> observer) {
    try {
      AuthenticationSession session =
          authenticate.authenticate(
              new AuthenticateLocalCommand(
                  requestId(request.getRequestId()),
                  channel(request.getChannel()),
                  request.getContact(),
                  request.getPassword(),
                  address(request.getClientAddress().getAddress())));
      observer.onNext(
          AuthenticateLocalResponse.newBuilder()
              .setIdentitySessionId(session.sessionId())
              .setRefreshFamilyId(session.refreshFamilyId().toString())
              .setRefreshCredential(session.refreshCredential())
              .setRefreshIdleExpiresAt(timestamp(session.idleExpiresAt()))
              .setRefreshAbsoluteExpiresAt(timestamp(session.absoluteExpiresAt()))
              .setSessionMode(mode(session.mode()))
              .setUserId(session.userId().toString())
              .setSelectedTenantId(
                  session.selectedTenantId() == null ? "" : session.selectedTenantId().toString())
              .setSelectedMembershipId(
                  session.selectedMembershipId() == null
                      ? ""
                      : session.selectedMembershipId().toString())
              .build());
      observer.onCompleted();
    } catch (AuthenticationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void refreshSession(
      RefreshSessionRequest request, StreamObserver<RefreshSessionResponse> observer) {
    try {
      AuthenticationSession session =
          refresh.refresh(
              new RefreshSessionCommand(
                  requestId(request.getRequestId()), request.getRefreshCredential()));
      observer.onNext(
          RefreshSessionResponse.newBuilder()
              .setIdentitySessionId(session.sessionId())
              .setRefreshFamilyId(session.refreshFamilyId().toString())
              .setRefreshCredential(session.refreshCredential())
              .setRefreshIdleExpiresAt(timestamp(session.idleExpiresAt()))
              .setRefreshAbsoluteExpiresAt(timestamp(session.absoluteExpiresAt()))
              .setSessionMode(mode(session.mode()))
              .setUserId(session.userId().toString())
              .setSelectedTenantId(
                  session.selectedTenantId() == null ? "" : session.selectedTenantId().toString())
              .setSelectedMembershipId(
                  session.selectedMembershipId() == null
                      ? ""
                      : session.selectedMembershipId().toString())
              .build());
      observer.onCompleted();
    } catch (AuthenticationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void logoutCurrent(
      LogoutCurrentRequest request, StreamObserver<LogoutCurrentResponse> observer) {
    try {
      logoutCurrent.logout(
          new LogoutCurrentCommand(
              requestId(request.getRequestId()), request.getRefreshCredential()));
      observer.onNext(LogoutCurrentResponse.newBuilder().setAccepted(true).build());
      observer.onCompleted();
    } catch (AuthenticationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void logoutAll(LogoutAllRequest request, StreamObserver<LogoutAllResponse> observer) {
    try {
      logoutAll.logoutAll(
          new LogoutAllCommand(requestId(request.getRequestId()), request.getRefreshCredential()));
      observer.onNext(LogoutAllResponse.newBuilder().setAccepted(true).build());
      observer.onCompleted();
    } catch (AuthenticationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void issueAudienceAccessToken(
      IssueAudienceAccessTokenRequest request,
      StreamObserver<IssueAudienceAccessTokenResponse> observer) {
    try {
      SignedAccessToken token =
          issueAccessToken.issue(
              new IssueAudienceAccessTokenCommand(
                  requestId(request.getRequestId()),
                  request.getRefreshCredential(),
                  request.getAudience()));
      observer.onNext(
          IssueAudienceAccessTokenResponse.newBuilder()
              .setAccessToken(token.token())
              .setExpiresAt(timestamp(token.expiresAt()))
              .build());
      observer.onCompleted();
    } catch (AuthenticationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  private static UUID requestId(String value) {
    if (value == null
        || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("Invalid request ID");
    }
    return UUID.fromString(value);
  }

  private static RegistrationChannel channel(
      com.sajtech.identity.contract.v1.AuthenticationChannel value) {
    return switch (value) {
      case AUTHENTICATION_CHANNEL_EMAIL -> RegistrationChannel.EMAIL;
      case AUTHENTICATION_CHANNEL_PHONE -> RegistrationChannel.PHONE;
      default -> throw new IllegalArgumentException("Unsupported authentication channel");
    };
  }

  private static byte[] address(ByteString value) {
    byte[] result = value.toByteArray();
    if (result.length != 4 && result.length != 16) {
      throw new IllegalArgumentException("Trusted client address is invalid");
    }
    return result;
  }

  private static com.sajtech.identity.contract.v1.AuthenticationSessionMode mode(
      com.sajtech.identity.application.authentication.model.AuthenticationSessionMode value) {
    return switch (value) {
      case AUTHENTICATED_ONBOARDING ->
          com.sajtech.identity.contract.v1.AuthenticationSessionMode
              .AUTHENTICATION_SESSION_MODE_AUTHENTICATED_ONBOARDING;
      case TENANT_AUTHENTICATED ->
          com.sajtech.identity.contract.v1.AuthenticationSessionMode
              .AUTHENTICATION_SESSION_MODE_TENANT_AUTHENTICATED;
    };
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static Status status(AuthenticationException exception) {
    AuthenticationError error = exception.error();
    Status base =
        switch (error) {
          case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
          case INVALID_CREDENTIALS, INVALID_SESSION, REFRESH_REUSE_DETECTED ->
              Status.UNAUTHENTICATED;
          case TENANT_SELECTION_REQUIRED -> Status.FAILED_PRECONDITION;
          case AUDIENCE_NOT_ALLOWED -> Status.PERMISSION_DENIED;
          case QUOTA_EXCEEDED, AUTHENTICATION_OVERLOADED -> Status.RESOURCE_EXHAUSTED;
          case QUOTA_UNAVAILABLE,
              QUOTA_TIME_SOURCE_UNHEALTHY,
              QUOTA_CAPACITY_UNHEALTHY,
              SIGNING_KEY_UNAVAILABLE,
              SESSION_STATE_INVALID ->
              Status.UNAVAILABLE;
        };
    String description =
        error == AuthenticationError.REFRESH_REUSE_DETECTED ? "INVALID_SESSION" : error.name();
    return base.withDescription(description);
  }
}
