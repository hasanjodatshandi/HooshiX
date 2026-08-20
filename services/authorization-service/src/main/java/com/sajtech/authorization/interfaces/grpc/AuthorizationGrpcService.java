package com.sajtech.authorization.interfaces.grpc;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.*;
import com.sajtech.authorization.contract.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.*;

public final class AuthorizationGrpcService
    extends AuthorizationServiceGrpc.AuthorizationServiceImplBase {
  private final com.sajtech.authorization.application.AuthorizationService service;

  public AuthorizationGrpcService(
      com.sajtech.authorization.application.AuthorizationService service) {
    this.service = service;
  }

  @Override
  public void checkPermission(CheckPermissionRequest r, StreamObserver<CheckPermissionResponse> o) {
    run(
        o,
        () -> {
          service.checkPermission(
              id(r.getTenantId()), id(r.getMembershipId()), r.getPermissionKey());
          return CheckPermissionResponse.getDefaultInstance();
        });
  }

  @Override
  public void checkPlatformPermission(
      CheckPlatformPermissionRequest r, StreamObserver<CheckPlatformPermissionResponse> o) {
    run(
        o,
        () -> {
          service.checkPlatformPermission(id(r.getUserId()), r.getPermissionKey());
          return CheckPlatformPermissionResponse.getDefaultInstance();
        });
  }

  @Override
  public void listPermissions(ListPermissionsRequest r, StreamObserver<ListPermissionsResponse> o) {
    run(
        o,
        () -> {
          var rows = service.listPermissions(actor(), r.getPageSize(), pageText(r.getPageToken()));
          var b = ListPermissionsResponse.newBuilder();
          for (var p : rows)
            b.addPermissions(
                PermissionView.newBuilder()
                    .setPermissionKey(p.key())
                    .setScope(p.scope())
                    .setLifecycle(p.lifecycle()));
          if (rows.size() == pageSize(r.getPageSize())) b.setNextPageToken(rows.getLast().key());
          return b.build();
        });
  }

  @Override
  public void listRoles(ListRolesRequest r, StreamObserver<ListRolesResponse> o) {
    run(
        o,
        () -> {
          var rows = service.listRoles(actor(), r.getPageSize(), pageId(r.getPageToken()));
          var b = ListRolesResponse.newBuilder();
          for (var role : rows) b.addRoles(view(role));
          if (rows.size() == pageSize(r.getPageSize()))
            b.setNextPageToken(rows.getLast().roleId().toString());
          return b.build();
        });
  }

  @Override
  public void getRole(GetRoleRequest r, StreamObserver<GetRoleResponse> o) {
    run(
        o,
        () ->
            GetRoleResponse.newBuilder()
                .setRole(view(service.getRole(actor(), id(r.getRoleId()))))
                .build());
  }

  @Override
  public void getMembershipAuthorization(
      GetMembershipAuthorizationRequest r, StreamObserver<GetMembershipAuthorizationResponse> o) {
    run(
        o,
        () -> {
          var m = service.getMembershipAuthorization(actor(), id(r.getMembershipId()));
          var b =
              MembershipAuthorizationView.newBuilder()
                  .setMembershipId(m.membershipId().toString())
                  .setAuthoritativeForAccessDecisions(false);
          m.roleIds().forEach(x -> b.addRoleIds(x.toString()));
          m.overrides()
              .forEach(
                  x ->
                      b.addOverrides(
                          MembershipPermissionOverrideView.newBuilder()
                              .setPermissionKey(x.permissionKey())
                              .setDecision(x.decision())));
          return GetMembershipAuthorizationResponse.newBuilder().setAuthorization(b).build();
        });
  }

  @Override
  public void createRole(CreateRoleRequest r, StreamObserver<RoleResponse> o) {
    run(
        o,
        () ->
            RoleResponse.newBuilder()
                .setRole(
                    view(
                        service.createRole(
                            actor(),
                            request(r.getRequestId()),
                            r.getName(),
                            r.getDescription(),
                            r.getPermissionKeysList())))
                .build());
  }

  @Override
  public void updateRole(UpdateRoleRequest r, StreamObserver<RoleResponse> o) {
    run(
        o,
        () ->
            RoleResponse.newBuilder()
                .setRole(
                    view(
                        service.updateRole(
                            actor(),
                            request(r.getRequestId()),
                            id(r.getRoleId()),
                            r.getExpectedVersion(),
                            r.getName(),
                            r.getDescription())))
                .build());
  }

  @Override
  public void archiveRole(ArchiveRoleRequest r, StreamObserver<RoleResponse> o) {
    run(
        o,
        () ->
            RoleResponse.newBuilder()
                .setRole(
                    view(
                        service.archiveRole(
                            actor(),
                            request(r.getRequestId()),
                            id(r.getRoleId()),
                            r.getExpectedVersion())))
                .build());
  }

  @Override
  public void replaceRolePermissions(
      ReplaceRolePermissionsRequest r, StreamObserver<RoleResponse> o) {
    run(
        o,
        () ->
            RoleResponse.newBuilder()
                .setRole(
                    view(
                        service.replaceRolePermissions(
                            actor(),
                            request(r.getRequestId()),
                            id(r.getRoleId()),
                            r.getExpectedVersion(),
                            r.getPermissionKeysList(),
                            r.getReason())))
                .build());
  }

  @Override
  public void assignRoleToMembership(
      AssignRoleToMembershipRequest r, StreamObserver<MutationResponse> o) {
    run(
        o,
        () -> {
          service.assignRole(
              actor(),
              request(r.getRequestId()),
              id(r.getMembershipId()),
              id(r.getRoleId()),
              r.getReason());
          return accepted();
        });
  }

  @Override
  public void removeRoleFromMembership(
      RemoveRoleFromMembershipRequest r, StreamObserver<MutationResponse> o) {
    run(
        o,
        () -> {
          service.removeRole(
              actor(),
              request(r.getRequestId()),
              id(r.getMembershipId()),
              id(r.getRoleId()),
              r.getReason());
          return accepted();
        });
  }

  @Override
  public void setMembershipPermissionOverride(
      SetMembershipPermissionOverrideRequest r, StreamObserver<MutationResponse> o) {
    run(
        o,
        () -> {
          service.setOverride(
              actor(),
              request(r.getRequestId()),
              id(r.getMembershipId()),
              r.getPermissionKey(),
              r.getDecision(),
              r.getReason());
          return accepted();
        });
  }

  @Override
  public void removeMembershipPermissionOverride(
      RemoveMembershipPermissionOverrideRequest r, StreamObserver<MutationResponse> o) {
    run(
        o,
        () -> {
          service.removeOverride(
              actor(),
              request(r.getRequestId()),
              id(r.getMembershipId()),
              r.getPermissionKey(),
              r.getReason());
          return accepted();
        });
  }

  @Override
  public void provisionTenantOwner(
      ProvisionTenantOwnerRequest r, StreamObserver<LifecycleCommandResponse> o) {
    run(
        o,
        () -> {
          service.provisionOwner(
              request(r.getRequestId()),
              id(r.getTenantId()),
              id(r.getMembershipId()),
              id(r.getUserId()));
          return lifecycleAccepted();
        });
  }

  @Override
  public void provisionTenantMember(
      ProvisionTenantMemberRequest r, StreamObserver<LifecycleCommandResponse> o) {
    run(
        o,
        () -> {
          service.provisionMember(
              request(r.getRequestId()),
              id(r.getTenantId()),
              id(r.getMembershipId()),
              id(r.getUserId()));
          return lifecycleAccepted();
        });
  }

  @Override
  public void applyTenantLifecycle(
      ApplyTenantLifecycleRequest r, StreamObserver<LifecycleCommandResponse> o) {
    run(
        o,
        () -> {
          service.applyTenantLifecycle(
              request(r.getRequestId()), id(r.getTenantId()), r.getLifecycle());
          return lifecycleAccepted();
        });
  }

  @Override
  public void prepareMembershipRemoval(
      PrepareMembershipRemovalRequest r, StreamObserver<PrepareMembershipRemovalResponse> o) {
    run(
        o,
        () -> {
          service.prepareRemoval(
              request(r.getRequestId()), id(r.getTenantId()), id(r.getMembershipId()));
          return PrepareMembershipRemovalResponse.newBuilder().setPrepared(true).build();
        });
  }

  @Override
  public void finalizeMembershipRemoval(
      FinalizeMembershipRemovalRequest r, StreamObserver<LifecycleCommandResponse> o) {
    run(
        o,
        () -> {
          service.finalizeRemoval(
              request(r.getRequestId()), id(r.getTenantId()), id(r.getMembershipId()));
          return lifecycleAccepted();
        });
  }

  @Override
  public void cancelMembershipRemovalPreparation(
      CancelMembershipRemovalPreparationRequest r, StreamObserver<LifecycleCommandResponse> o) {
    run(
        o,
        () -> {
          service.cancelRemoval(
              request(r.getRequestId()), id(r.getTenantId()), id(r.getMembershipId()));
          return lifecycleAccepted();
        });
  }

  private static ActorContext actor() {
    ActorContext a = JwtActorServerInterceptor.ACTOR.get();
    if (a == null)
      throw new AuthorizationException(
          AuthorizationError.INVALID_ACCESS_TOKEN, "Access token is missing");
    return a;
  }

  private static RoleView view(RoleModel r) {
    return RoleView.newBuilder()
        .setRoleId(r.roleId().toString())
        .setName(r.name())
        .setDescription(r.description())
        .setKind(r.kind())
        .setLifecycle(r.lifecycle())
        .setVersion(r.version())
        .addAllPermissionKeys(r.permissionKeys())
        .build();
  }

  private static MutationResponse accepted() {
    return MutationResponse.newBuilder().setAccepted(true).build();
  }

  private static LifecycleCommandResponse lifecycleAccepted() {
    return LifecycleCommandResponse.newBuilder().setAccepted(true).build();
  }

  private static int pageSize(int value) {
    return value == 0 ? 50 : value;
  }

  private static String pageText(String value) {
    if (value == null || value.isEmpty()) return null;
    if (value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl))
      throw invalid();
    return value;
  }

  private static UUID pageId(String value) {
    return value == null || value.isEmpty() ? null : id(value);
  }

  private static UUID id(String value) {
    try {
      UUID v = UUID.fromString(value);
      if (!v.toString().equals(value)) throw invalid();
      return v;
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private static UUID request(String value) {
    UUID v = id(value);
    if (v.version() != 4) throw invalid();
    return v;
  }

  private static AuthorizationException invalid() {
    return new AuthorizationException(AuthorizationError.INVALID_ARGUMENT, "Invalid argument");
  }

  private static Status status(AuthorizationException e) {
    return switch (e.error()) {
      case AUTHORIZATION_DENIED -> Status.PERMISSION_DENIED;
      case INVALID_ACCESS_TOKEN -> Status.UNAUTHENTICATED;
      case ROLE_NOT_FOUND -> Status.NOT_FOUND;
      case ROLE_NAME_CONFLICT, REQUEST_ID_CONFLICT -> Status.ALREADY_EXISTS;
      case SYSTEM_ROLE_IMMUTABLE,
          ROLE_ARCHIVED,
          PERMISSION_RETIRED,
          MEMBERSHIP_NOT_ACTIVE,
          TENANT_NOT_AUTHORIZABLE,
          LAST_TENANT_OWNER,
          STALE_ROLE_VERSION ->
          Status.FAILED_PRECONDITION;
      case AUTHORIZATION_OVERLOADED, LIMIT_EXCEEDED, QUOTA_EXCEEDED -> Status.RESOURCE_EXHAUSTED;
      case AUTHORIZATION_UNAVAILABLE, QUOTA_UNAVAILABLE -> Status.UNAVAILABLE;
      case PERMISSION_UNKNOWN, INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
    };
  }

  private static <T> void run(StreamObserver<T> observer, Call<T> call) {
    try {
      observer.onNext(call.run());
      observer.onCompleted();
    } catch (AuthorizationException e) {
      observer.onError(status(e).withDescription(e.error().name()).asRuntimeException());
    } catch (RuntimeException e) {
      observer.onError(
          Status.INTERNAL.withDescription("AUTHORIZATION_FAILED").asRuntimeException());
    }
  }

  @FunctionalInterface
  private interface Call<T> {
    T run();
  }
}
