package com.sajtech.identity.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.identity.application.tenant.TenantError;
import com.sajtech.identity.application.tenant.TenantException;
import com.sajtech.identity.application.tenant.model.AcceptedInvitation;
import com.sajtech.identity.application.tenant.model.InvitationMutation;
import com.sajtech.identity.application.tenant.model.InvitationResult;
import com.sajtech.identity.application.tenant.model.InvitationSummary;
import com.sajtech.identity.application.tenant.model.SelectableTenantList;
import com.sajtech.identity.application.tenant.model.TenantCreation;
import com.sajtech.identity.application.tenant.model.TenantLifecycleMutation;
import com.sajtech.identity.application.tenant.model.TenantSelection;
import com.sajtech.identity.application.tenant.port.in.TenantLifecycle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservedTenantLifecycleTest {
  @Test
  void recordsBoundedOperationOutcomeAndReleasesInFlightGauge() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    TenantLifecycle delegate = new FixedTenantLifecycle();
    ObservedTenantLifecycle observed =
        new ObservedTenantLifecycle(delegate, ObservationRegistry.create(), meters);

    observed.createTenant(UUID.randomUUID(), "refresh", "Acme", "acme");

    assertThat(
            meters
                .get("identity.tenant.duration")
                .tag("operation", "CREATE_TENANT")
                .tag("outcome", "SUCCESS")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(meters.get("identity.tenant.in_flight").gauge().value()).isZero();

    assertThatThrownBy(
            () -> observed.inviteExistingUser(UUID.randomUUID(), "refresh", UUID.randomUUID()))
        .isInstanceOf(TenantException.class);
    assertThat(
            meters
                .get("identity.tenant.duration")
                .tag("operation", "INVITE_EXISTING_USER")
                .tag("outcome", "AUTHORIZATION_UNAVAILABLE")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(meters.get("identity.tenant.in_flight").gauge().value()).isZero();
  }

  private static final class FixedTenantLifecycle implements TenantLifecycle {
    @Override
    public TenantCreation createTenant(
        UUID requestId, String refreshCredential, String name, String slug) {
      return new TenantCreation(UUID.randomUUID(), UUID.randomUUID(), "PROVISIONING");
    }

    @Override
    public SelectableTenantList listSelectable(String refreshCredential) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TenantSelection selectTenant(
        UUID requestId, String refreshCredential, UUID membershipId, String audience) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InvitationResult inviteExistingUser(
        UUID requestId, String refreshCredential, UUID targetContactId) {
      throw new TenantException(TenantError.AUTHORIZATION_UNAVAILABLE, "unavailable");
    }

    @Override
    public AcceptedInvitation acceptInvitation(
        UUID requestId, String refreshCredential, UUID invitationId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void removeMembership(
        UUID requestId, String refreshCredential, UUID targetMembershipId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TenantLifecycleMutation suspendTenant(
        UUID requestId, String refreshCredential, UUID tenantId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TenantLifecycleMutation resumeTenant(
        UUID requestId, String refreshCredential, UUID tenantId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TenantLifecycleMutation deleteTenant(
        UUID requestId, String refreshCredential, UUID tenantId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TenantLifecycleMutation restoreTenant(
        UUID requestId, String refreshCredential, UUID tenantId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<InvitationSummary> listReceivedInvitations(String refreshCredential) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<InvitationSummary> listTenantInvitations(String refreshCredential) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InvitationMutation declineInvitation(
        UUID requestId, String refreshCredential, UUID invitationId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InvitationMutation revokeInvitation(
        UUID requestId, String refreshCredential, UUID invitationId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InvitationResult reissueInvitation(
        UUID requestId, String refreshCredential, UUID invitationId) {
      throw new UnsupportedOperationException();
    }
  }
}
