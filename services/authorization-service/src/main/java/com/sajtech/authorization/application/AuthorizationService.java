package com.sajtech.authorization.application;

import com.sajtech.authorization.application.model.*;
import com.sajtech.authorization.application.port.out.*;
import java.text.Normalizer;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class AuthorizationService {
  private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+");
  private static final Set<String> SYSTEM_NAMES =
      Set.of("tenant_owner", "tenant_admin", "tenant_member");
  private final AuthorizationStore store;
  private final AdminQuota quota;
  private final IntentFingerprint fingerprints;
  private final Clock clock;

  public AuthorizationService(
      AuthorizationStore store, AdminQuota quota, IntentFingerprint fingerprints, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.quota = Objects.requireNonNull(quota);
    this.fingerprints = Objects.requireNonNull(fingerprints);
    this.clock = Objects.requireNonNull(clock);
  }

  public void checkPermission(UUID tenantId, UUID membershipId, String permissionKey) {
    validatePermission(permissionKey);
    if (!store.checkPermission(required(tenantId), required(membershipId), permissionKey)) denied();
  }

  public void checkPlatformPermission(UUID userId, String permissionKey) {
    validatePermission(permissionKey);
    UUID trustedUser = required(userId);
    if (!store.checkPlatformPermission(trustedUser, permissionKey)) {
      AuthorizationException rejection =
          new AuthorizationException(
              AuthorizationError.AUTHORIZATION_DENIED, "Authorization denied");
      rejected("PLATFORM_PERMISSION_REJECTED", null, trustedUser, null, rejection);
      throw rejection;
    }
  }

  public List<PermissionModel> listPermissions(ActorContext actor, int limit, String after) {
    actor(actor);
    return managed(
        actor,
        "LIST_PERMISSIONS_REJECTED",
        null,
        () -> store.listPermissions(actor, page(limit), after));
  }

  public List<RoleModel> listRoles(ActorContext actor, int limit, UUID after) {
    actor(actor);
    return managed(
        actor, "LIST_ROLES_REJECTED", null, () -> store.listRoles(actor, page(limit), after));
  }

  public RoleModel getRole(ActorContext actor, UUID roleId) {
    actor(actor);
    UUID target = required(roleId);
    return managed(actor, "GET_ROLE_REJECTED", target, () -> store.getRole(actor, target));
  }

  public MembershipAuthorizationModel getMembershipAuthorization(
      ActorContext actor, UUID membershipId) {
    actor(actor);
    UUID target = required(membershipId);
    return managed(
        actor,
        "GET_MEMBERSHIP_AUTHORIZATION_REJECTED",
        target,
        () -> store.getMembershipAuthorization(actor, target));
  }

  public RoleModel createRole(
      ActorContext actor,
      UUID requestId,
      String name,
      String description,
      List<String> permissions) {
    actor(actor);
    return managed(
        actor,
        "CREATE_ROLE_REJECTED",
        null,
        () -> {
          request(requestId);
          Name n = name(name);
          String d = description(description);
          List<String> p = permissions(permissions);
          quota.acquire(actor, Math.max(1, p.size()));
          return store.createRole(
              actor,
              requestId,
              fp(actor, "CREATE_ROLE", requestId, n.key(), d, String.join(",", p)),
              n.display(),
              n.key(),
              d,
              p,
              clock.instant());
        });
  }

  public RoleModel updateRole(
      ActorContext actor,
      UUID requestId,
      UUID roleId,
      long expectedVersion,
      String name,
      String description) {
    actor(actor);
    return managed(
        actor,
        "UPDATE_ROLE_REJECTED",
        roleId,
        () -> {
          request(requestId);
          required(roleId);
          version(expectedVersion);
          Name n = name(name);
          String d = description(description);
          quota.acquire(actor, 1);
          return store.updateRole(
              actor,
              requestId,
              fp(actor, "UPDATE_ROLE", requestId, roleId, expectedVersion, n.key(), d),
              roleId,
              expectedVersion,
              n.display(),
              n.key(),
              d,
              clock.instant());
        });
  }

  public RoleModel archiveRole(
      ActorContext actor, UUID requestId, UUID roleId, long expectedVersion) {
    actor(actor);
    return managed(
        actor,
        "ARCHIVE_ROLE_REJECTED",
        roleId,
        () -> {
          request(requestId);
          required(roleId);
          version(expectedVersion);
          quota.acquire(actor, 1);
          return store.archiveRole(
              actor,
              requestId,
              fp(actor, "ARCHIVE_ROLE", requestId, roleId, expectedVersion),
              roleId,
              expectedVersion,
              clock.instant());
        });
  }

  public RoleModel replaceRolePermissions(
      ActorContext actor,
      UUID requestId,
      UUID roleId,
      long expectedVersion,
      List<String> permissions,
      String reason) {
    actor(actor);
    return managed(
        actor,
        "REPLACE_ROLE_PERMISSIONS_REJECTED",
        roleId,
        () -> {
          request(requestId);
          required(roleId);
          version(expectedVersion);
          List<String> p = permissions(permissions);
          String why = reason(reason);
          List<String> current = store.rolePermissionKeysForQuota(actor.tenantId(), roleId);
          Set<String> delta = new HashSet<>(current);
          for (String permission : p) {
            if (!delta.add(permission)) delta.remove(permission);
          }
          if (delta.size() > 100) limit();
          quota.acquire(actor, Math.max(1, delta.size()));
          return store.replaceRolePermissions(
              actor,
              requestId,
              fp(
                  actor,
                  "REPLACE_ROLE_PERMISSIONS",
                  requestId,
                  roleId,
                  expectedVersion,
                  String.join(",", p),
                  why),
              roleId,
              expectedVersion,
              p,
              why,
              clock.instant());
        });
  }

  public void assignRole(
      ActorContext actor, UUID requestId, UUID membershipId, UUID roleId, String reason) {
    actor(actor);
    managedVoid(
        actor,
        "ASSIGN_ROLE_REJECTED",
        membershipId,
        () -> {
          request(requestId);
          required(membershipId);
          required(roleId);
          String why = reason(reason);
          quota.acquire(actor, 1);
          store.assignRole(
              actor,
              requestId,
              fp(actor, "ASSIGN_ROLE", requestId, membershipId, roleId, why),
              membershipId,
              roleId,
              why,
              clock.instant());
        });
  }

  public void removeRole(
      ActorContext actor, UUID requestId, UUID membershipId, UUID roleId, String reason) {
    actor(actor);
    managedVoid(
        actor,
        "REMOVE_ROLE_REJECTED",
        membershipId,
        () -> {
          request(requestId);
          required(membershipId);
          required(roleId);
          String why = reason(reason);
          quota.acquire(actor, 1);
          store.removeRole(
              actor,
              requestId,
              fp(actor, "REMOVE_ROLE", requestId, membershipId, roleId, why),
              membershipId,
              roleId,
              why,
              clock.instant());
        });
  }

  public void setOverride(
      ActorContext actor,
      UUID requestId,
      UUID membershipId,
      String key,
      String decision,
      String reason) {
    actor(actor);
    managedVoid(
        actor,
        "SET_OVERRIDE_REJECTED",
        membershipId,
        () -> {
          request(requestId);
          required(membershipId);
          validatePermission(key);
          if (!Set.of("GRANT", "DENY").contains(decision)) invalid();
          String why = reason(reason);
          quota.acquire(actor, 1);
          store.setOverride(
              actor,
              requestId,
              fp(actor, "SET_OVERRIDE", requestId, membershipId, key, decision, why),
              membershipId,
              key,
              decision,
              why,
              clock.instant());
        });
  }

  public void removeOverride(
      ActorContext actor, UUID requestId, UUID membershipId, String key, String reason) {
    actor(actor);
    managedVoid(
        actor,
        "REMOVE_OVERRIDE_REJECTED",
        membershipId,
        () -> {
          request(requestId);
          required(membershipId);
          validatePermission(key);
          String why = reason(reason);
          quota.acquire(actor, 1);
          store.removeOverride(
              actor,
              requestId,
              fp(actor, "REMOVE_OVERRIDE", requestId, membershipId, key, why),
              membershipId,
              key,
              why,
              clock.instant());
        });
  }

  public void provisionOwner(UUID requestId, UUID tenantId, UUID membershipId, UUID userId) {
    required(tenantId);
    lifecycleVoid(
        "PROVISION_OWNER_REJECTED",
        tenantId,
        membershipId,
        () -> {
          request(requestId);
          required(membershipId);
          required(userId);
          store.provisionOwner(
              requestId,
              fingerprints.fingerprint(
                  "PROVISION_OWNER",
                  requestId.toString(),
                  tenantId.toString(),
                  membershipId.toString(),
                  userId.toString()),
              tenantId,
              membershipId,
              userId,
              clock.instant());
        });
  }

  public void provisionMember(UUID requestId, UUID tenantId, UUID membershipId, UUID userId) {
    required(tenantId);
    lifecycleVoid(
        "PROVISION_MEMBER_REJECTED",
        tenantId,
        membershipId,
        () -> {
          request(requestId);
          required(membershipId);
          required(userId);
          store.provisionMember(
              requestId,
              fingerprints.fingerprint(
                  "PROVISION_MEMBER",
                  requestId.toString(),
                  tenantId.toString(),
                  membershipId.toString(),
                  userId.toString()),
              tenantId,
              membershipId,
              userId,
              clock.instant());
        });
  }

  public void applyTenantLifecycle(UUID requestId, UUID tenantId, String lifecycle) {
    required(tenantId);
    lifecycleVoid(
        "TENANT_LIFECYCLE_REJECTED",
        tenantId,
        tenantId,
        () -> {
          request(requestId);
          if (!Set.of("PROVISIONING", "ACTIVE", "SUSPENDED", "DELETING", "DELETED")
              .contains(lifecycle)) invalid();
          store.applyTenantLifecycle(
              requestId,
              fingerprints.fingerprint(
                  "TENANT_LIFECYCLE", requestId.toString(), tenantId.toString(), lifecycle),
              tenantId,
              lifecycle,
              clock.instant());
        });
  }

  public void prepareRemoval(UUID requestId, UUID tenantId, UUID membershipId) {
    required(tenantId);
    lifecycleVoid(
        "MEMBERSHIP_REMOVAL_PREPARE_REJECTED",
        tenantId,
        membershipId,
        () -> {
          request(requestId);
          required(membershipId);
          store.prepareMembershipRemoval(
              requestId,
              fingerprints.fingerprint(
                  "PREPARE_REMOVAL",
                  requestId.toString(),
                  tenantId.toString(),
                  membershipId.toString()),
              tenantId,
              membershipId,
              clock.instant());
        });
  }

  public void finalizeRemoval(UUID requestId, UUID tenantId, UUID membershipId) {
    required(tenantId);
    lifecycleVoid(
        "MEMBERSHIP_REMOVAL_FINALIZE_REJECTED",
        tenantId,
        membershipId,
        () -> {
          request(requestId);
          required(membershipId);
          store.finalizeMembershipRemoval(
              requestId,
              fingerprints.fingerprint(
                  "FINALIZE_REMOVAL",
                  requestId.toString(),
                  tenantId.toString(),
                  membershipId.toString()),
              tenantId,
              membershipId,
              clock.instant());
        });
  }

  public void cancelRemoval(UUID requestId, UUID tenantId, UUID membershipId) {
    required(tenantId);
    lifecycleVoid(
        "MEMBERSHIP_REMOVAL_CANCEL_REJECTED",
        tenantId,
        membershipId,
        () -> {
          request(requestId);
          required(membershipId);
          store.cancelMembershipRemoval(
              requestId,
              fingerprints.fingerprint(
                  "CANCEL_REMOVAL",
                  requestId.toString(),
                  tenantId.toString(),
                  membershipId.toString()),
              tenantId,
              membershipId,
              clock.instant());
        });
  }

  private FingerprintDigest fp(ActorContext a, String op, Object... parts) {
    String[] values = new String[parts.length + 4];
    values[0] = a.userId().toString();
    values[1] = a.tenantId().toString();
    values[2] = a.membershipId().toString();
    values[3] = a.sessionId();
    for (int i = 0; i < parts.length; i++) values[i + 4] = String.valueOf(parts[i]);
    return fingerprints.fingerprint(op, values);
  }

  private static List<String> permissions(List<String> input) {
    if (input == null || input.size() > 200) limit();
    TreeSet<String> sorted = new TreeSet<>();
    for (String p : input) {
      validatePermission(p);
      sorted.add(p);
    }
    if (sorted.size() != input.size()) invalid();
    return List.copyOf(sorted);
  }

  private static Name name(String input) {
    if (input == null) invalid();
    String display = Normalizer.normalize(input.trim(), Normalizer.Form.NFC);
    if (display.isEmpty() || display.codePointCount(0, display.length()) > 80 || controls(display))
      invalid();
    String key = display.toLowerCase(Locale.ROOT);
    if (SYSTEM_NAMES.contains(key))
      throw new AuthorizationException(
          AuthorizationError.SYSTEM_ROLE_IMMUTABLE, "Reserved role name");
    return new Name(display, key);
  }

  private static String description(String input) {
    String value = Normalizer.normalize(input == null ? "" : input.trim(), Normalizer.Form.NFC);
    if (value.codePointCount(0, value.length()) > 500 || controls(value)) invalid();
    return value;
  }

  private static String reason(String input) {
    String value = description(input);
    if (value.isEmpty()) invalid();
    return value;
  }

  private static void validatePermission(String p) {
    if (p == null
        || p.length() > 128
        || (!PERMISSION.matcher(p).matches() && !p.equals("platform.legal_hold.manage"))) invalid();
  }

  private static int page(int n) {
    if (n == 0) return 50;
    if (n < 1 || n > 200) invalid();
    return n;
  }

  private static void actor(ActorContext a) {
    if (a == null
        || a.userId() == null
        || a.tenantId() == null
        || a.membershipId() == null
        || a.sessionId() == null
        || a.sessionId().isBlank()) invalid();
  }

  private static <T> T required(T v) {
    if (v == null) invalid();
    return v;
  }

  private static void request(UUID r) {
    if (r == null || r.version() != 4) invalid();
  }

  private static void version(long v) {
    if (v < 1) invalid();
  }

  private static boolean controls(String s) {
    return s.codePoints().anyMatch(Character::isISOControl);
  }

  private static void denied() {
    throw new AuthorizationException(
        AuthorizationError.AUTHORIZATION_DENIED, "Authorization denied");
  }

  private static void invalid() {
    throw new AuthorizationException(AuthorizationError.INVALID_ARGUMENT, "Invalid argument");
  }

  private static void limit() {
    throw new AuthorizationException(AuthorizationError.LIMIT_EXCEEDED, "Limit exceeded");
  }

  private <T> T managed(ActorContext actor, String eventCode, UUID target, Supplier<T> action) {
    try {
      return action.get();
    } catch (AuthorizationException rejection) {
      rejected(eventCode, actor.tenantId(), actor.userId(), target, rejection);
      throw rejection;
    }
  }

  private void managedVoid(ActorContext actor, String eventCode, UUID target, Runnable action) {
    managed(
        actor,
        eventCode,
        target,
        () -> {
          action.run();
          return null;
        });
  }

  private <T> T lifecycle(String eventCode, UUID tenantId, UUID target, Supplier<T> action) {
    try {
      return action.get();
    } catch (AuthorizationException rejection) {
      rejected(eventCode, tenantId, null, target, rejection);
      throw rejection;
    }
  }

  private void lifecycleVoid(String eventCode, UUID tenantId, UUID target, Runnable action) {
    lifecycle(
        eventCode,
        tenantId,
        target,
        () -> {
          action.run();
          return null;
        });
  }

  private void rejected(
      String eventCode,
      UUID tenantId,
      UUID actorUserId,
      UUID target,
      AuthorizationException rejection) {
    try {
      store.recordRejection(
          eventCode, tenantId, actorUserId, target, rejection.error(), null, clock.instant());
    } catch (RuntimeException auditFailure) {
      throw new AuthorizationException(
          AuthorizationError.AUTHORIZATION_UNAVAILABLE,
          "Authorization rejection audit is unavailable",
          auditFailure);
    }
  }

  private record Name(String display, String key) {}
}
