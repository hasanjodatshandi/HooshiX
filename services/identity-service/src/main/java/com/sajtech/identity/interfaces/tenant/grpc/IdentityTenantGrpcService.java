package com.sajtech.identity.interfaces.tenant.grpc;

import com.google.protobuf.Timestamp;
import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.model.*;
import com.sajtech.identity.contract.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;

public final class IdentityTenantGrpcService
    extends IdentityTenantServiceGrpc.IdentityTenantServiceImplBase {
  private final TenantLifecycleService service;

  public IdentityTenantGrpcService(TenantLifecycleService service) {
    this.service = service;
  }

  @Override
  public void createTenant(CreateTenantRequest r, StreamObserver<CreateTenantResponse> o) {
    run(
        o,
        () -> {
          var x =
              service.createTenant(
                  request(r.getRequestId()), r.getRefreshCredential(), r.getName(), r.getSlug());
          return CreateTenantResponse.newBuilder()
              .setTenantId(x.tenantId().toString())
              .setCreatorMembershipId(x.membershipId().toString())
              .setLifecycle(x.lifecycle())
              .build();
        });
  }

  @Override
  public void listSelectableTenants(
      ListSelectableTenantsRequest r, StreamObserver<ListSelectableTenantsResponse> o) {
    run(
        o,
        () -> {
          var x = service.listSelectable(r.getRefreshCredential());
          var b = ListSelectableTenantsResponse.newBuilder();
          for (var t : x.tenants())
            b.addTenants(
                com.sajtech.identity.contract.v1.SelectableTenant.newBuilder()
                    .setTenantId(t.tenantId().toString())
                    .setMembershipId(t.membershipId().toString())
                    .setName(t.name())
                    .setSlug(t.slug()));
          if (x.suggestedMembershipId() != null)
            b.setSuggestedMembershipId(x.suggestedMembershipId().toString());
          return b.build();
        });
  }

  @Override
  public void selectTenant(SelectTenantRequest r, StreamObserver<SelectTenantResponse> o) {
    run(
        o,
        () -> {
          var x =
              service.selectTenant(
                  request(r.getRequestId()),
                  r.getRefreshCredential(),
                  id(r.getMembershipId()),
                  r.getAudience());
          return SelectTenantResponse.newBuilder()
              .setIdentitySessionId(x.sessionId())
              .setRefreshFamilyId(x.refreshFamilyId().toString())
              .setRefreshCredential(x.refreshCredential())
              .setRefreshIdleExpiresAt(ts(x.idleExpiresAt()))
              .setRefreshAbsoluteExpiresAt(ts(x.absoluteExpiresAt()))
              .setTenantId(x.tenantId().toString())
              .setMembershipId(x.membershipId().toString())
              .setAccessToken(x.accessToken().token())
              .setAccessTokenExpiresAt(ts(x.accessToken().expiresAt()))
              .build();
        });
  }

  @Override
  public void inviteExistingUser(
      InviteExistingUserRequest r, StreamObserver<InviteExistingUserResponse> o) {
    run(
        o,
        () -> {
          var x =
              service.inviteExistingUser(
                  request(r.getRequestId()), r.getRefreshCredential(), id(r.getTargetContactId()));
          return InviteExistingUserResponse.newBuilder()
              .setInvitationId(x.invitationId().toString())
              .setExpiresAt(ts(x.expiresAt()))
              .build();
        });
  }

  @Override
  public void acceptInvitation(
      AcceptInvitationRequest r, StreamObserver<AcceptInvitationResponse> o) {
    run(
        o,
        () -> {
          var x =
              service.acceptInvitation(
                  request(r.getRequestId()), r.getRefreshCredential(), id(r.getInvitationId()));
          return AcceptInvitationResponse.newBuilder()
              .setTenantId(x.tenantId().toString())
              .setMembershipId(x.membershipId().toString())
              .build();
        });
  }

  @Override
  public void removeMembership(
      RemoveMembershipRequest r, StreamObserver<RemoveMembershipResponse> o) {
    run(
        o,
        () -> {
          service.removeMembership(
              request(r.getRequestId()), r.getRefreshCredential(), id(r.getMembershipId()));
          return RemoveMembershipResponse.newBuilder().setAccepted(true).build();
        });
  }

  private static UUID id(String v) {
    try {
      UUID x = UUID.fromString(v);
      if (!x.toString().equals(v)) throw new IllegalArgumentException();
      return x;
    } catch (RuntimeException e) {
      throw new TenantException(TenantError.INVALID_ARGUMENT, "Invalid UUID");
    }
  }

  private static UUID request(String v) {
    UUID x = id(v);
    if (x.version() != 4)
      throw new TenantException(TenantError.INVALID_ARGUMENT, "request_id must be UUIDv4");
    return x;
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
  }

  private static Status status(TenantException e) {
    return switch (e.error()) {
      case INVALID_SESSION -> Status.UNAUTHENTICATED;
      case AUTHORIZATION_DENIED -> Status.PERMISSION_DENIED;
      case TENANT_SLUG_CONFLICT, INVITATION_ALREADY_PENDING, REQUEST_ID_CONFLICT ->
          Status.ALREADY_EXISTS;
      case INVITATION_NOT_FOUND -> Status.NOT_FOUND;
      case LAST_TENANT_OWNER,
          MEMBERSHIP_NOT_ACTIVE,
          TENANT_NOT_SELECTABLE,
          INVITATION_NOT_PENDING,
          INVITATION_EXPIRED,
          INVITATION_TARGET_MISMATCH,
          VERIFIED_CONTACT_REQUIRED,
          SESSION_STATE_INVALID ->
          Status.FAILED_PRECONDITION;
      case AUTHORIZATION_UNAVAILABLE -> Status.UNAVAILABLE;
      case INVALID_ARGUMENT, AUDIENCE_NOT_ALLOWED -> Status.INVALID_ARGUMENT;
    };
  }

  private static <T> void run(StreamObserver<T> o, Call<T> c) {
    try {
      o.onNext(c.run());
      o.onCompleted();
    } catch (TenantException e) {
      o.onError(status(e).withDescription(e.error().name()).asRuntimeException());
    } catch (RuntimeException e) {
      o.onError(Status.INTERNAL.withDescription("TENANT_OPERATION_FAILED").asRuntimeException());
    }
  }

  @FunctionalInterface
  private interface Call<T> {
    T run();
  }
}
