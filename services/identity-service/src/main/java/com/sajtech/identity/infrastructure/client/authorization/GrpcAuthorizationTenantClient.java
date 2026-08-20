package com.sajtech.identity.infrastructure.client.authorization;

import com.sajtech.authorization.contract.v1.*;
import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.port.out.AuthorizationTenantPort;
import io.grpc.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class GrpcAuthorizationTenantClient implements AuthorizationTenantPort {
  private final ManagedChannel channel;

  public GrpcAuthorizationTenantClient(ManagedChannel channel) {
    this.channel = channel;
  }

  @Override
  public void checkPermission(UUID tenant, UUID membership, String key) {
    try {
      AuthorizationServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(300, TimeUnit.MILLISECONDS)
          .checkPermission(
              CheckPermissionRequest.newBuilder()
                  .setTenantId(tenant.toString())
                  .setMembershipId(membership.toString())
                  .setPermissionKey(key)
                  .build());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  @Override
  public void provisionOwner(UUID request, UUID tenant, UUID membership, UUID user) {
    call900(
        () ->
            AuthorizationServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(900, TimeUnit.MILLISECONDS)
                .provisionTenantOwner(
                    ProvisionTenantOwnerRequest.newBuilder()
                        .setRequestId(request.toString())
                        .setTenantId(tenant.toString())
                        .setMembershipId(membership.toString())
                        .setUserId(user.toString())
                        .build()));
  }

  @Override
  public void provisionMember(UUID request, UUID tenant, UUID membership, UUID user) {
    call900(
        () ->
            AuthorizationServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(900, TimeUnit.MILLISECONDS)
                .provisionTenantMember(
                    ProvisionTenantMemberRequest.newBuilder()
                        .setRequestId(request.toString())
                        .setTenantId(tenant.toString())
                        .setMembershipId(membership.toString())
                        .setUserId(user.toString())
                        .build()));
  }

  @Override
  public void applyTenantLifecycle(UUID request, UUID tenant, String lifecycle) {
    call900(
        () ->
            AuthorizationServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(900, TimeUnit.MILLISECONDS)
                .applyTenantLifecycle(
                    ApplyTenantLifecycleRequest.newBuilder()
                        .setRequestId(request.toString())
                        .setTenantId(tenant.toString())
                        .setLifecycle(lifecycle)
                        .build()));
  }

  @Override
  public void prepareMembershipRemoval(UUID request, UUID tenant, UUID membership) {
    try {
      AuthorizationServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(300, TimeUnit.MILLISECONDS)
          .prepareMembershipRemoval(
              PrepareMembershipRemovalRequest.newBuilder()
                  .setRequestId(request.toString())
                  .setTenantId(tenant.toString())
                  .setMembershipId(membership.toString())
                  .build());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  @Override
  public void finalizeMembershipRemoval(UUID request, UUID tenant, UUID membership) {
    call900(
        () ->
            AuthorizationServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(900, TimeUnit.MILLISECONDS)
                .finalizeMembershipRemoval(
                    FinalizeMembershipRemovalRequest.newBuilder()
                        .setRequestId(request.toString())
                        .setTenantId(tenant.toString())
                        .setMembershipId(membership.toString())
                        .build()));
  }

  @Override
  public void cancelMembershipRemoval(UUID request, UUID tenant, UUID membership) {
    call900(
        () ->
            AuthorizationServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(900, TimeUnit.MILLISECONDS)
                .cancelMembershipRemovalPreparation(
                    CancelMembershipRemovalPreparationRequest.newBuilder()
                        .setRequestId(request.toString())
                        .setTenantId(tenant.toString())
                        .setMembershipId(membership.toString())
                        .build()));
  }

  private void call900(Call c) {
    try {
      c.run();
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  private static TenantException map(StatusRuntimeException e) {
    Status.Code c = e.getStatus().getCode();
    String d = e.getStatus().getDescription();
    if (c == Status.Code.PERMISSION_DENIED)
      return new TenantException(TenantError.AUTHORIZATION_DENIED, "Authorization denied", e);
    if (c == Status.Code.FAILED_PRECONDITION && "LAST_TENANT_OWNER".equals(d))
      return new TenantException(TenantError.LAST_TENANT_OWNER, "Last tenant owner", e);
    if (c == Status.Code.ALREADY_EXISTS)
      return new TenantException(
          TenantError.REQUEST_ID_CONFLICT, "Authorization request conflict", e);
    return new TenantException(
        TenantError.AUTHORIZATION_UNAVAILABLE, "Authorization unavailable", e);
  }

  @FunctionalInterface
  private interface Call {
    void run();
  }
}
