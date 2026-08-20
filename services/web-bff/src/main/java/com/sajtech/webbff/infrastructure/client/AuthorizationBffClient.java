package com.sajtech.webbff.infrastructure.client;

import com.sajtech.authorization.contract.v1.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.AuthorizationGateway;
import com.sajtech.webbff.application.port.out.AuthorizationGateway.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class AuthorizationBffClient implements AuthorizationGateway {
  private static final Metadata.Key<String> AUTH =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private final ManagedChannel channel;

  public AuthorizationBffClient(ManagedChannel channel) {
    this.channel = Objects.requireNonNull(channel);
  }

  public List<PermissionDto> permissions(String token, int size, String page) {
    try {
      var r =
          stub(token)
              .listPermissions(
                  ListPermissionsRequest.newBuilder()
                      .setPageSize(size)
                      .setPageToken(page == null ? "" : page)
                      .build());
      return r.getPermissionsList().stream()
          .map(p -> new PermissionDto(p.getPermissionKey(), p.getScope(), p.getLifecycle()))
          .toList();
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public RolePage roles(String token, int size, String page) {
    try {
      var r =
          stub(token)
              .listRoles(
                  ListRolesRequest.newBuilder()
                      .setPageSize(size)
                      .setPageToken(page == null ? "" : page)
                      .build());
      return new RolePage(
          r.getRolesList().stream().map(AuthorizationBffClient::role).toList(),
          r.getNextPageToken());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public RoleDto role(String token, UUID roleId) {
    try {
      return role(
          stub(token)
              .getRole(GetRoleRequest.newBuilder().setRoleId(roleId.toString()).build())
              .getRole());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public MembershipAuthorizationDto membership(String token, UUID membershipId) {
    try {
      var a =
          stub(token)
              .getMembershipAuthorization(
                  GetMembershipAuthorizationRequest.newBuilder()
                      .setMembershipId(membershipId.toString())
                      .build())
              .getAuthorization();
      return new MembershipAuthorizationDto(
          uuid(a.getMembershipId()),
          a.getRoleIdsList().stream().map(UUID::fromString).toList(),
          a.getOverridesList().stream()
              .map(x -> new OverrideDto(x.getPermissionKey(), x.getDecision()))
              .toList());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public RoleDto createRole(
      String token, UUID requestId, String name, String description, List<String> permissions) {
    try {
      return role(
          stub(token)
              .createRole(
                  CreateRoleRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setName(name)
                      .setDescription(description == null ? "" : description)
                      .addAllPermissionKeys(permissions)
                      .build())
              .getRole());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public RoleDto updateRole(
      String token, UUID requestId, UUID roleId, long version, String name, String description) {
    try {
      return role(
          stub(token)
              .updateRole(
                  UpdateRoleRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRoleId(roleId.toString())
                      .setExpectedVersion(version)
                      .setName(name)
                      .setDescription(description == null ? "" : description)
                      .build())
              .getRole());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public RoleDto archiveRole(String token, UUID requestId, UUID roleId, long version) {
    try {
      return role(
          stub(token)
              .archiveRole(
                  ArchiveRoleRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRoleId(roleId.toString())
                      .setExpectedVersion(version)
                      .build())
              .getRole());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public RoleDto replacePermissions(
      String token,
      UUID requestId,
      UUID roleId,
      long version,
      List<String> permissions,
      String reason) {
    try {
      return role(
          stub(token)
              .replaceRolePermissions(
                  ReplaceRolePermissionsRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRoleId(roleId.toString())
                      .setExpectedVersion(version)
                      .addAllPermissionKeys(permissions)
                      .setReason(reason)
                      .build())
              .getRole());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public void assignRole(String token, UUID requestId, UUID membership, UUID role, String reason) {
    mutate(
        () ->
            stub(token)
                .assignRoleToMembership(
                    AssignRoleToMembershipRequest.newBuilder()
                        .setRequestId(requestId.toString())
                        .setMembershipId(membership.toString())
                        .setRoleId(role.toString())
                        .setReason(reason)
                        .build()));
  }

  public void removeRole(String token, UUID requestId, UUID membership, UUID role, String reason) {
    mutate(
        () ->
            stub(token)
                .removeRoleFromMembership(
                    RemoveRoleFromMembershipRequest.newBuilder()
                        .setRequestId(requestId.toString())
                        .setMembershipId(membership.toString())
                        .setRoleId(role.toString())
                        .setReason(reason)
                        .build()));
  }

  public void setOverride(
      String token,
      UUID requestId,
      UUID membership,
      String permission,
      String decision,
      String reason) {
    mutate(
        () ->
            stub(token)
                .setMembershipPermissionOverride(
                    SetMembershipPermissionOverrideRequest.newBuilder()
                        .setRequestId(requestId.toString())
                        .setMembershipId(membership.toString())
                        .setPermissionKey(permission)
                        .setDecision(decision)
                        .setReason(reason)
                        .build()));
  }

  public void removeOverride(
      String token, UUID requestId, UUID membership, String permission, String reason) {
    mutate(
        () ->
            stub(token)
                .removeMembershipPermissionOverride(
                    RemoveMembershipPermissionOverrideRequest.newBuilder()
                        .setRequestId(requestId.toString())
                        .setMembershipId(membership.toString())
                        .setPermissionKey(permission)
                        .setReason(reason)
                        .build()));
  }

  private void mutate(Call c) {
    try {
      c.run();
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  private AuthorizationServiceGrpc.AuthorizationServiceBlockingStub stub(String token) {
    if (token == null || token.isBlank())
      throw new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Authorization token is missing");
    Metadata m = new Metadata();
    m.put(AUTH, "Bearer " + token);
    return AuthorizationServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(m))
        .withDeadlineAfter(1400, TimeUnit.MILLISECONDS);
  }

  private static RoleDto role(RoleView r) {
    return new RoleDto(
        uuid(r.getRoleId()),
        r.getName(),
        r.getDescription(),
        r.getKind(),
        r.getLifecycle(),
        r.getVersion(),
        List.copyOf(r.getPermissionKeysList()));
  }

  private static UUID uuid(String v) {
    try {
      return UUID.fromString(v);
    } catch (IllegalArgumentException e) {
      throw new BffException(
          BffError.DEPENDENCY_UNAVAILABLE, "Authorization returned invalid UUID", e);
    }
  }

  private static BffException map(StatusRuntimeException e) {
    return switch (e.getStatus().getCode()) {
      case PERMISSION_DENIED, UNAUTHENTICATED ->
          new BffException(BffError.AUTHORIZATION_DENIED, "Authorization denied", e);
      case RESOURCE_EXHAUSTED ->
          new BffException(BffError.RATE_LIMITED, "Authorization quota exceeded", e);
      case INVALID_ARGUMENT, ALREADY_EXISTS, FAILED_PRECONDITION, NOT_FOUND ->
          new BffException(BffError.INVALID_REQUEST, "Authorization request is invalid", e);
      default ->
          new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Authorization is unavailable", e);
    };
  }

  @FunctionalInterface
  private interface Call {
    void run();
  }
}
