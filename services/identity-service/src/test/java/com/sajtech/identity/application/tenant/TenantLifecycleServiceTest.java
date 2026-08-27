package com.sajtech.identity.application.tenant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.tenant.model.*;
import com.sajtech.identity.application.tenant.port.out.*;
import com.sajtech.identity.application.tenant.service.TenantIntentEncoder;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class TenantLifecycleServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
  private final RefreshDigest currentDigest =
      new RefreshDigest("k1", "refresh-hmac-v1", new byte[32]);
  private final RefreshDigest nextDigest =
      new RefreshDigest("k1", "refresh-hmac-v1", bytes((byte) 2));
  private final SessionCredentialPort credentials = mock(SessionCredentialPort.class);
  private final AuthenticationStore authentication = mock(AuthenticationStore.class);
  private final TenantStore tenants = mock(TenantStore.class);
  private final AuthorizationTenantPort authorization = mock(AuthorizationTenantPort.class);
  private final AccessTokenSigner signer = mock(AccessTokenSigner.class);
  private final TrackingTransactionRunner transactions = new TrackingTransactionRunner();
  private final TenantLifecycleService service =
      new TenantLifecycleService(
          Set.of("authorization-service"),
          new RefreshCredentialLookup(credentials),
          credentials,
          authentication,
          tenants,
          authorization,
          signer,
          new TenantIntentEncoder(),
          transactions,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void credentials() {
    when(credentials.digestCandidates("refresh")).thenReturn(List.of(currentDigest));
  }

  @Test
  void staleLastSelectedPreferenceIsNotSuggested() {
    LockedRefreshCredential current = onboarding();
    when(authentication.lockRefreshCredential(currentDigest)).thenReturn(Optional.of(current));
    SelectableTenant a =
        new SelectableTenant(UUID.randomUUID(), UUID.randomUUID(), "A", "tenant-a");
    SelectableTenant b =
        new SelectableTenant(UUID.randomUUID(), UUID.randomUUID(), "B", "tenant-b");
    when(tenants.listSelectable(current.userId())).thenReturn(List.of(a, b));
    when(tenants.lastSelectedMembership(current.userId())).thenReturn(UUID.randomUUID());
    SelectableTenantList result = service.listSelectable("refresh");
    assertThat(result.tenants()).containsExactly(a, b);
    assertThat(result.suggestedMembershipId()).isNull();
  }

  @Test
  void selectingTenantRotatesRefreshAndSignsExactTenantContext() {
    LockedRefreshCredential current = onboarding();
    UUID tenantId = UUID.randomUUID(), membershipId = UUID.randomUUID();
    when(authentication.lockRefreshCredential(currentDigest)).thenReturn(Optional.of(current));
    when(tenants.listSelectable(current.userId()))
        .thenReturn(List.of(new SelectableTenant(tenantId, membershipId, "Acme", "acme")));
    when(credentials.newRefreshCredential())
        .thenReturn(new GeneratedRefreshCredential("next-refresh", nextDigest));
    when(signer.sign(any())).thenReturn(new SignedAccessToken("jwt", NOW.plusSeconds(300)));
    TenantSelection result =
        service.selectTenant(UUID.randomUUID(), "refresh", membershipId, "authorization-service");
    assertThat(result.tenantId()).isEqualTo(tenantId);
    assertThat(result.membershipId()).isEqualTo(membershipId);
    assertThat(result.refreshCredential()).isEqualTo("next-refresh");
    ArgumentCaptor<AccessTokenContext> token = ArgumentCaptor.forClass(AccessTokenContext.class);
    verify(signer).sign(token.capture());
    assertThat(token.getValue().tenantId()).isEqualTo(tenantId);
    assertThat(token.getValue().membershipId()).isEqualTo(membershipId);
    assertThat(token.getValue().audience()).isEqualTo("authorization-service");
    verify(tenants)
        .selectContext(
            eq(current),
            eq(membershipId),
            eq(tenantId),
            any(UUID.class),
            eq(nextDigest),
            eq(NOW),
            eq(NOW.plus(Duration.ofDays(7))));
  }

  @Test
  void invitationAuthorizationCallOccursOutsideDatabaseTransaction() {
    LockedRefreshCredential current = tenantAuthenticated();
    when(authentication.lockRefreshCredential(currentDigest)).thenReturn(Optional.of(current));
    when(tenants.isSelectable(
            current.userId(), current.selectedTenantId(), current.selectedMembershipId()))
        .thenReturn(true);
    UUID contact = UUID.randomUUID();
    when(tenants.createInvitation(
            any(),
            eq(current.userId()),
            eq(current.selectedTenantId()),
            eq(contact),
            any(byte[].class),
            eq(NOW),
            eq(NOW.plus(Duration.ofDays(7)))))
        .thenReturn(new InvitationResult(UUID.randomUUID(), NOW.plus(Duration.ofDays(7))));
    doAnswer(
            invocation -> {
              assertThat(transactions.inTransaction()).isFalse();
              return null;
            })
        .when(authorization)
        .checkPermission(
            current.selectedTenantId(), current.selectedMembershipId(), "membership.role.assign");
    service.inviteExistingUser(UUID.randomUUID(), "refresh", contact);
    verify(authorization)
        .checkPermission(
            current.selectedTenantId(), current.selectedMembershipId(), "membership.role.assign");
  }

  @Test
  void membershipRemovalPrepareIsRemoteOutsideTransactionAndLocalCommitIsInside() {
    LockedRefreshCredential current = tenantAuthenticated();
    when(authentication.lockRefreshCredential(currentDigest)).thenReturn(Optional.of(current));
    when(tenants.isSelectable(
            current.userId(), current.selectedTenantId(), current.selectedMembershipId()))
        .thenReturn(true);
    UUID requestId = UUID.randomUUID(), target = UUID.randomUUID();
    when(tenants.createRemovalIntent(
            eq(requestId),
            eq(current.userId()),
            eq(current.selectedTenantId()),
            eq(current.selectedMembershipId()),
            eq(target),
            any(byte[].class),
            eq(NOW)))
        .thenReturn(
            new RemovalPreparation(
                requestId,
                current.selectedTenantId(),
                target,
                current.selectedMembershipId(),
                false));
    doAnswer(
            i -> {
              assertThat(transactions.inTransaction()).isFalse();
              return null;
            })
        .when(authorization)
        .checkPermission(
            current.selectedTenantId(), current.selectedMembershipId(), "membership.role.assign");
    doAnswer(
            i -> {
              assertThat(transactions.inTransaction()).isFalse();
              return null;
            })
        .when(authorization)
        .prepareMembershipRemoval(requestId, current.selectedTenantId(), target);
    doAnswer(
            i -> {
              assertThat(transactions.inTransaction()).isTrue();
              return null;
            })
        .when(tenants)
        .commitMembershipRemoval(requestId, NOW);
    service.removeMembership(requestId, "refresh", target);
    verify(authorization).prepareMembershipRemoval(requestId, current.selectedTenantId(), target);
    verify(tenants).commitMembershipRemoval(requestId, NOW);
  }

  @Test
  void platformLifecyclePermissionIsCheckedOutsideTransactionBeforeDurableCommand() {
    LockedRefreshCredential current = onboarding();
    when(authentication.lockRefreshCredential(currentDigest)).thenReturn(Optional.of(current));
    UUID requestId = UUID.randomUUID(), tenantId = UUID.randomUUID();
    doAnswer(
            invocation -> {
              assertThat(transactions.inTransaction()).isFalse();
              return null;
            })
        .when(authorization)
        .checkPlatformPermission(current.userId(), "platform.tenant.suspend");
    when(tenants.requestTenantLifecycle(
            eq(requestId),
            eq(current.userId()),
            eq(tenantId),
            eq("ACTIVE"),
            eq("SUSPENDED"),
            any(byte[].class),
            eq(NOW)))
        .thenAnswer(
            invocation -> {
              assertThat(transactions.inTransaction()).isTrue();
              return new TenantLifecycleMutation(tenantId, "ACTIVE", "SUSPENDED", true);
            });

    TenantLifecycleMutation result = service.suspendTenant(requestId, "refresh", tenantId);

    assertThat(result.pending()).isTrue();
    verify(authorization).checkPlatformPermission(current.userId(), "platform.tenant.suspend");
  }

  @Test
  void deleteChecksSelectedTenantOwnerPermissionOutsideTransaction() {
    LockedRefreshCredential current = tenantAuthenticated();
    when(authentication.lockRefreshCredential(currentDigest)).thenReturn(Optional.of(current));
    when(tenants.isSelectable(
            current.userId(), current.selectedTenantId(), current.selectedMembershipId()))
        .thenReturn(true);
    UUID requestId = UUID.randomUUID();
    doAnswer(
            invocation -> {
              assertThat(transactions.inTransaction()).isFalse();
              return null;
            })
        .when(authorization)
        .checkPermission(
            current.selectedTenantId(), current.selectedMembershipId(), "tenant.delete");
    when(tenants.requestTenantLifecycle(
            eq(requestId),
            eq(current.userId()),
            eq(current.selectedTenantId()),
            eq("ACTIVE"),
            eq("DELETING"),
            any(byte[].class),
            eq(NOW)))
        .thenReturn(
            new TenantLifecycleMutation(current.selectedTenantId(), "ACTIVE", "DELETED", true));

    TenantLifecycleMutation result =
        service.deleteTenant(requestId, "refresh", current.selectedTenantId());

    assertThat(result.targetLifecycle()).isEqualTo("DELETED");
    verify(authorization)
        .checkPermission(
            current.selectedTenantId(), current.selectedMembershipId(), "tenant.delete");
  }

  private LockedRefreshCredential onboarding() {
    return new LockedRefreshCredential(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "s".repeat(43),
        UUID.randomUUID(),
        "ACTIVE",
        "ACTIVE",
        "ACTIVE",
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
        null,
        null,
        NOW.plus(Duration.ofDays(7)),
        NOW.plus(Duration.ofDays(30)));
  }

  private LockedRefreshCredential tenantAuthenticated() {
    return new LockedRefreshCredential(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "s".repeat(43),
        UUID.randomUUID(),
        "ACTIVE",
        "ACTIVE",
        "ACTIVE",
        AuthenticationSessionMode.TENANT_AUTHENTICATED,
        UUID.randomUUID(),
        UUID.randomUUID(),
        NOW.plus(Duration.ofDays(7)),
        NOW.plus(Duration.ofDays(30)));
  }

  private static byte[] bytes(byte v) {
    byte[] b = new byte[32];
    Arrays.fill(b, v);
    return b;
  }

  private static final class TrackingTransactionRunner implements TransactionRunner {
    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

    public <T> T required(java.util.function.Supplier<T> work) {
      boolean previous = active.get();
      active.set(true);
      try {
        return work.get();
      } finally {
        active.set(previous);
      }
    }

    boolean inTransaction() {
      return active.get();
    }
  }
}
