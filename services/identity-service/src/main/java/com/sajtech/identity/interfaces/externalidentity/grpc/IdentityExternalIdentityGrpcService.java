package com.sajtech.identity.interfaces.externalidentity.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.sajtech.identity.application.authentication.model.AuthenticationSession;
import com.sajtech.identity.application.externalidentity.*;
import com.sajtech.identity.application.externalidentity.model.ExternalIdentityEvidence;
import com.sajtech.identity.application.externalidentity.port.in.ExternalIdentityManagement;
import com.sajtech.identity.contract.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;

public final class IdentityExternalIdentityGrpcService
    extends IdentityExternalIdentityServiceGrpc.IdentityExternalIdentityServiceImplBase {
  private final ExternalIdentityManagement identities;

  public IdentityExternalIdentityGrpcService(ExternalIdentityManagement identities) {
    this.identities = identities;
  }

  @Override
  public void establishSession(
      EstablishSessionRequest request, StreamObserver<EstablishSessionResponse> observer) {
    try {
      AuthenticationSession session =
          identities.establish(
              requestId(request.getRequestId()),
              evidence(request.getEvidence()),
              address(request.getClientAddress().getAddress()));
      observer.onNext(
          EstablishSessionResponse.newBuilder()
              .setAuthentication(authenticationResponse(session))
              .build());
      observer.onCompleted();
    } catch (ExternalIdentityException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void link(LinkRequest request, StreamObserver<LinkResponse> observer) {
    try {
      AuthenticationSession session =
          identities.link(
              requestId(request.getRequestId()),
              request.getRefreshCredential(),
              evidence(request.getEvidence()),
              address(request.getClientAddress().getAddress()));
      observer.onNext(
          LinkResponse.newBuilder().setSession(externalIdentitySession(session)).build());
      observer.onCompleted();
    } catch (ExternalIdentityException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void unlink(UnlinkRequest request, StreamObserver<UnlinkResponse> observer) {
    try {
      AuthenticationSession session =
          identities.unlink(
              requestId(request.getRequestId()),
              request.getRefreshCredential(),
              request.getIssuer());
      observer.onNext(
          UnlinkResponse.newBuilder().setSession(externalIdentitySession(session)).build());
      observer.onCompleted();
    } catch (ExternalIdentityException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void getStatus(GetStatusRequest request, StreamObserver<GetStatusResponse> observer) {
    try {
      boolean linked =
          identities.googleLinked(
              requestId(request.getRequestId()), request.getRefreshCredential());
      observer.onNext(GetStatusResponse.newBuilder().setGoogleLinked(linked).build());
      observer.onCompleted();
    } catch (ExternalIdentityException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  private static ExternalIdentityEvidence evidence(
      com.sajtech.identity.contract.v1.ExternalIdentityEvidence value) {
    return new ExternalIdentityEvidence(
        value.getEvidenceId().toByteArray(),
        instant(value.getEvidenceIssuedAt()),
        value.getIssuer(),
        value.getSubject(),
        value.getMetadataVersion(),
        emptyToNull(value.getEmail()),
        value.getEmailVerified(),
        emptyToNull(value.getGivenName()),
        emptyToNull(value.getFamilyName()));
  }

  private static AuthenticateLocalResponse authenticationResponse(AuthenticationSession session) {
    AuthenticateLocalResponse.Builder response =
        AuthenticateLocalResponse.newBuilder()
            .setSessionMode(mode(session))
            .setUserId(session.userId().toString());
    if (session.mode()
        == com.sajtech.identity.application.authentication.model.AuthenticationSessionMode
            .MFA_REQUIRED) {
      response.setMfaChallenge(session.mfaChallenge());
    } else {
      response
          .setIdentitySessionId(session.sessionId())
          .setRefreshFamilyId(session.refreshFamilyId().toString())
          .setRefreshCredential(session.refreshCredential())
          .setRefreshIdleExpiresAt(timestamp(session.idleExpiresAt()))
          .setRefreshAbsoluteExpiresAt(timestamp(session.absoluteExpiresAt()))
          .setSelectedTenantId(
              session.selectedTenantId() == null ? "" : session.selectedTenantId().toString())
          .setSelectedMembershipId(
              session.selectedMembershipId() == null
                  ? ""
                  : session.selectedMembershipId().toString());
    }
    return response.build();
  }

  private static ExternalIdentitySession externalIdentitySession(AuthenticationSession session) {
    return ExternalIdentitySession.newBuilder()
        .setIdentitySessionId(session.sessionId())
        .setRefreshFamilyId(session.refreshFamilyId().toString())
        .setRefreshCredential(session.refreshCredential())
        .setRefreshIdleExpiresAt(timestamp(session.idleExpiresAt()))
        .setRefreshAbsoluteExpiresAt(timestamp(session.absoluteExpiresAt()))
        .setSessionMode(mode(session))
        .setUserId(session.userId().toString())
        .setSelectedTenantId(
            session.selectedTenantId() == null ? "" : session.selectedTenantId().toString())
        .setSelectedMembershipId(
            session.selectedMembershipId() == null ? "" : session.selectedMembershipId().toString())
        .build();
  }

  private static com.sajtech.identity.contract.v1.AuthenticationSessionMode mode(
      AuthenticationSession session) {
    return switch (session.mode()) {
      case AUTHENTICATED_ONBOARDING ->
          com.sajtech.identity.contract.v1.AuthenticationSessionMode
              .AUTHENTICATION_SESSION_MODE_AUTHENTICATED_ONBOARDING;
      case TENANT_AUTHENTICATED ->
          com.sajtech.identity.contract.v1.AuthenticationSessionMode
              .AUTHENTICATION_SESSION_MODE_TENANT_AUTHENTICATED;
      case MFA_REQUIRED ->
          com.sajtech.identity.contract.v1.AuthenticationSessionMode
              .AUTHENTICATION_SESSION_MODE_MFA_REQUIRED;
    };
  }

  private static Status status(ExternalIdentityException exception) {
    ExternalIdentityError error = exception.error();
    Status base =
        switch (error) {
          case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
          case INVALID_EVIDENCE, EVIDENCE_EXPIRED, INVALID_SESSION -> Status.UNAUTHENTICATED;
          case EVIDENCE_REPLAY, IDENTITY_ALREADY_LINKED -> Status.ALREADY_EXISTS;
          case ACCOUNT_LINK_REQUIRED,
              IDENTITY_NOT_LINKED,
              LAST_AUTHENTICATION_METHOD,
              RECENT_AUTH_REQUIRED ->
              Status.FAILED_PRECONDITION;
          case SESSION_STATE_INVALID -> Status.UNAVAILABLE;
        };
    return base.withDescription(error.name());
  }

  private static UUID requestId(String value) {
    UUID id = UUID.fromString(value);
    if (id.version() != 4 || !id.toString().equals(value)) {
      throw new IllegalArgumentException("Request ID is invalid");
    }
    return id;
  }

  private static byte[] address(ByteString value) {
    byte[] result = value.toByteArray();
    if (result.length != 4 && result.length != 16) {
      throw new IllegalArgumentException("Trusted client address is invalid");
    }
    return result;
  }

  private static Instant instant(Timestamp value) {
    return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
