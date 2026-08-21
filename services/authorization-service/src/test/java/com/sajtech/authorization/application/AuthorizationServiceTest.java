package com.sajtech.authorization.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.authorization.application.model.*;
import com.sajtech.authorization.application.port.out.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class AuthorizationServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
  private final AuthorizationStore store = mock(AuthorizationStore.class);
  private final AdminQuota quota = mock(AdminQuota.class);
  private final IntentFingerprint fingerprints = mock(IntentFingerprint.class);
  private final ActorContext actor =
      new ActorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "s".repeat(43));
  private final FingerprintDigest digest =
      new FingerprintDigest("v1", "k1", Map.of("k1", new byte[32]));
  private final AuthorizationService service =
      new AuthorizationService(store, quota, fingerprints, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void fingerprint() {
    when(fingerprints.fingerprint(anyString(), any(String[].class))).thenReturn(digest);
  }

  @Test
  void checkPermissionIsDefaultDeny() {
    when(store.checkPermission(actor.tenantId(), actor.membershipId(), "tenant.read"))
        .thenReturn(false);
    assertThatThrownBy(
            () -> service.checkPermission(actor.tenantId(), actor.membershipId(), "tenant.read"))
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            e -> assertThat(e.error()).isEqualTo(AuthorizationError.AUTHORIZATION_DENIED));
  }

  @Test
  void reservedSystemRoleNameIsRejectedBeforeQuotaOrStore() {
    assertThatThrownBy(
            () ->
                service.createRole(
                    actor, UUID.randomUUID(), "tenant_owner", "", List.of("tenant.read")))
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            e -> assertThat(e.error()).isEqualTo(AuthorizationError.SYSTEM_ROLE_IMMUTABLE));
    verifyNoInteractions(quota);
    verify(store, never())
        .createRole(any(), any(), any(), anyString(), anyString(), anyString(), anyList(), any());
  }

  @Test
  void createRoleRejectsMoreThanOneHundredSemanticMutationsBeforeQuota() {
    List<String> permissions =
        java.util.stream.IntStream.range(0, 101).mapToObj(i -> "resource.p" + i).toList();
    assertThatThrownBy(
            () -> service.createRole(actor, UUID.randomUUID(), "bounded", "", permissions))
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            e -> assertThat(e.error()).isEqualTo(AuthorizationError.LIMIT_EXCEEDED));
    verifyNoInteractions(quota);
    verify(store, never())
        .createRole(any(), any(), any(), anyString(), anyString(), anyString(), anyList(), any());
  }

  @Test
  void replacePermissionsChargesActualSymmetricSetDelta() {
    UUID roleId = UUID.randomUUID(), requestId = UUID.randomUUID();
    when(store.rolePermissionKeysForQuota(actor.tenantId(), roleId))
        .thenReturn(List.of("tenant.read", "role.read", "role.create"));
    RoleModel result =
        new RoleModel(
            roleId,
            "custom",
            "",
            "CUSTOM",
            "ACTIVE",
            2,
            List.of("tenant.read", "role.read", "role.update"));
    when(store.replaceRolePermissions(
            eq(actor), eq(requestId), any(), eq(roleId), eq(1L), anyList(), eq("change"), eq(NOW)))
        .thenReturn(result);
    assertThat(
            service.replaceRolePermissions(
                actor,
                requestId,
                roleId,
                1,
                List.of("tenant.read", "role.read", "role.update"),
                "change"))
        .isSameAs(result);
    verify(quota).acquire(actor, 2);
  }

  @Test
  void replacePermissionsRejectsDeltaAboveOneHundredBeforeQuota() {
    UUID roleId = UUID.randomUUID();
    when(store.rolePermissionKeysForQuota(actor.tenantId(), roleId)).thenReturn(List.of());
    List<String> permissions =
        java.util.stream.IntStream.range(0, 101).mapToObj(i -> "resource.p" + i).toList();
    assertThatThrownBy(
            () ->
                service.replaceRolePermissions(
                    actor, UUID.randomUUID(), roleId, 1, permissions, "bulk"))
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            e -> assertThat(e.error()).isEqualTo(AuthorizationError.LIMIT_EXCEEDED));
    verifyNoInteractions(quota);
    verify(store, never())
        .replaceRolePermissions(
            any(), any(), any(), any(), anyLong(), anyList(), anyString(), any());
  }

  @Test
  void legalHoldPermissionKeyRemainsAcceptedBecauseItIsAnExplicitAdrKey() {
    when(store.checkPlatformPermission(actor.userId(), "platform.legal_hold.manage"))
        .thenReturn(true);
    assertThatCode(
            () -> service.checkPlatformPermission(actor.userId(), "platform.legal_hold.manage"))
        .doesNotThrowAnyException();
  }

  @Test
  void platformPermissionDenialCreatesDurableRejectionAudit() {
    when(store.checkPlatformPermission(actor.userId(), "platform.tenant.suspend"))
        .thenReturn(false);
    assertThatThrownBy(
            () -> service.checkPlatformPermission(actor.userId(), "platform.tenant.suspend"))
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            e -> assertThat(e.error()).isEqualTo(AuthorizationError.AUTHORIZATION_DENIED));
    verify(store)
        .recordRejection(
            "PLATFORM_PERMISSION_REJECTED",
            null,
            actor.userId(),
            null,
            AuthorizationError.AUTHORIZATION_DENIED,
            null,
            NOW);
  }
}
