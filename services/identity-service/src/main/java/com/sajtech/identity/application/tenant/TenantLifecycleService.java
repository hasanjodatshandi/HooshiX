package com.sajtech.identity.application.tenant;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.tenant.model.*;
import com.sajtech.identity.application.tenant.port.in.TenantLifecycle;
import com.sajtech.identity.application.tenant.port.out.*;
import com.sajtech.identity.application.tenant.service.TenantIntentEncoder;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class TenantLifecycleService implements TenantLifecycle {
  private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])");
  private static final Set<String> RESERVED =
      Set.of("admin", "api", "system", "platform", "www", "support");
  private static final Duration INVITATION_TTL = Duration.ofDays(7), IDLE = Duration.ofDays(7);
  private final Set<String> audiences;
  private final RefreshCredentialLookup lookup;
  private final SessionCredentialPort credentials;
  private final AuthenticationStore authStore;
  private final TenantStore tenants;
  private final AuthorizationTenantPort authorization;
  private final AccessTokenSigner signer;
  private final TenantIntentEncoder encoder;
  private final TransactionRunner transactions;
  private final Clock clock;

  public TenantLifecycleService(
      Set<String> audiences,
      RefreshCredentialLookup lookup,
      SessionCredentialPort credentials,
      AuthenticationStore authStore,
      TenantStore tenants,
      AuthorizationTenantPort authorization,
      AccessTokenSigner signer,
      TenantIntentEncoder encoder,
      TransactionRunner transactions,
      Clock clock) {
    this.audiences = Set.copyOf(audiences);
    this.lookup = lookup;
    this.credentials = credentials;
    this.authStore = authStore;
    this.tenants = tenants;
    this.authorization = authorization;
    this.signer = signer;
    this.encoder = encoder;
    this.transactions = transactions;
    this.clock = clock;
  }

  public TenantCreation createTenant(
      UUID requestId, String refreshCredential, String name, String slug) {
    request(requestId);
    Name n = name(name);
    String s = slug(slug);
    return withFingerprint(
        "CREATE_TENANT",
        material ->
            withSession(
                refreshCredential,
                false,
                current ->
                    tenants.createTenant(
                        requestId, current.userId(), n.value(), s, material, clock.instant())),
        requestId.toString(),
        n.value(),
        s);
  }

  public SelectableTenantList listSelectable(String refreshCredential) {
    return withSession(
        refreshCredential,
        false,
        current -> {
          List<SelectableTenant> list = tenants.listSelectable(current.userId());
          UUID last = tenants.lastSelectedMembership(current.userId());
          UUID suggested = null;
          if (list.size() == 1) suggested = list.getFirst().membershipId();
          else if (last != null && list.stream().anyMatch(x -> x.membershipId().equals(last)))
            suggested = last;
          return new SelectableTenantList(list, suggested);
        });
  }

  public TenantSelection selectTenant(
      UUID requestId, String refreshCredential, UUID membershipId, String audience) {
    request(requestId);
    required(membershipId);
    if (audience == null || !audiences.contains(audience) || audience.contains("*"))
      throw error(TenantError.AUDIENCE_NOT_ALLOWED, "Audience is not allowed");
    GeneratedRefreshCredential next = credentials.newRefreshCredential();
    return withSession(
        refreshCredential,
        false,
        current -> {
          SelectableTenant target =
              tenants.listSelectable(current.userId()).stream()
                  .filter(x -> x.membershipId().equals(membershipId))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          error(TenantError.MEMBERSHIP_NOT_ACTIVE, "Membership is not selectable"));
          Instant now = clock.instant(),
              nextIdle = min(now.plus(IDLE), current.absoluteExpiresAt());
          tenants.selectContext(
              current,
              membershipId,
              target.tenantId(),
              UUID.randomUUID(),
              next.digest(),
              now,
              nextIdle);
          SignedAccessToken token =
              signer.sign(
                  new AccessTokenContext(
                      current.userId(),
                      target.tenantId(),
                      membershipId,
                      current.sessionId(),
                      audience,
                      now));
          return new TenantSelection(
              current.sessionId(),
              current.refreshFamilyId(),
              next.encoded(),
              nextIdle,
              current.absoluteExpiresAt(),
              target.tenantId(),
              membershipId,
              token);
        });
  }

  public InvitationResult inviteExistingUser(
      UUID requestId, String refreshCredential, UUID targetContactId) {
    request(requestId);
    required(targetContactId);
    SessionAuthority authority =
        withSession(
            refreshCredential,
            true,
            c -> new SessionAuthority(c.userId(), c.selectedTenantId(), c.selectedMembershipId()));
    authorization.checkPermission(
        authority.tenantId(), authority.membershipId(), "membership.role.assign");
    return withFingerprint(
        "INVITE_EXISTING_USER",
        material ->
            withSession(
                refreshCredential,
                true,
                c -> {
                  if (!authority.tenantId().equals(c.selectedTenantId())
                      || !authority.membershipId().equals(c.selectedMembershipId()))
                    throw error(TenantError.INVALID_SESSION, "Tenant context changed");
                  Instant now = clock.instant();
                  return tenants.createInvitation(
                      requestId,
                      c.userId(),
                      c.selectedTenantId(),
                      targetContactId,
                      material,
                      now,
                      now.plus(INVITATION_TTL));
                }),
        requestId.toString(),
        authority.tenantId().toString(),
        targetContactId.toString());
  }

  public AcceptedInvitation acceptInvitation(
      UUID requestId, String refreshCredential, UUID invitationId) {
    request(requestId);
    required(invitationId);
    return withFingerprint(
        "ACCEPT_INVITATION",
        material ->
            withSession(
                refreshCredential,
                false,
                c ->
                    tenants.acceptInvitation(
                        requestId, c.userId(), invitationId, material, clock.instant())),
        requestId.toString(),
        invitationId.toString());
  }

  public void removeMembership(UUID requestId, String refreshCredential, UUID targetMembershipId) {
    request(requestId);
    required(targetMembershipId);
    withFingerprint(
        "REMOVE_MEMBERSHIP",
        material -> {
          RemovalPreparation prep =
              withSession(
                  refreshCredential,
                  true,
                  c ->
                      tenants.createRemovalIntent(
                          requestId,
                          c.userId(),
                          c.selectedTenantId(),
                          c.selectedMembershipId(),
                          targetMembershipId,
                          material,
                          clock.instant()));
          if (!prep.actorIsTarget())
            authorization.checkPermission(
                prep.tenantId(), prep.actorMembershipId(), "membership.role.assign");
          authorization.prepareMembershipRemoval(
              requestId, prep.tenantId(), prep.targetMembershipId());
          try {
            transactions.required(
                () -> {
                  tenants.commitMembershipRemoval(requestId, clock.instant());
                  return null;
                });
          } catch (RuntimeException failure) {
            try {
              transactions.required(
                  () -> {
                    tenants.enqueueRemovalCancel(requestId, clock.instant());
                    return null;
                  });
            } catch (RuntimeException suppressed) {
              failure.addSuppressed(suppressed);
            }
            throw failure;
          }
          return null;
        },
        requestId.toString(),
        targetMembershipId.toString());
  }

  @Override
  public TenantLifecycleMutation suspendTenant(
      UUID requestId, String refreshCredential, UUID tenantId) {
    return platformLifecycle(
        requestId,
        refreshCredential,
        required(tenantId),
        "platform.tenant.suspend",
        "SUSPEND_TENANT",
        "ACTIVE",
        "SUSPENDED");
  }

  @Override
  public TenantLifecycleMutation resumeTenant(
      UUID requestId, String refreshCredential, UUID tenantId) {
    return platformLifecycle(
        requestId,
        refreshCredential,
        required(tenantId),
        "platform.tenant.resume",
        "RESUME_TENANT",
        "SUSPENDED",
        "ACTIVE");
  }

  @Override
  public TenantLifecycleMutation deleteTenant(
      UUID requestId, String refreshCredential, UUID tenantId) {
    request(requestId);
    required(tenantId);
    SessionAuthority authority =
        withSession(
            refreshCredential,
            true,
            c -> new SessionAuthority(c.userId(), c.selectedTenantId(), c.selectedMembershipId()));
    if (!tenantId.equals(authority.tenantId()))
      throw error(TenantError.AUTHORIZATION_DENIED, "Selected tenant does not match");
    authorization.checkPermission(tenantId, authority.membershipId(), "tenant.delete");
    return withFingerprint(
        "DELETE_TENANT",
        material ->
            withSession(
                refreshCredential,
                true,
                c -> {
                  if (!authority.userId().equals(c.userId())
                      || !tenantId.equals(c.selectedTenantId())
                      || !authority.membershipId().equals(c.selectedMembershipId()))
                    throw error(TenantError.INVALID_SESSION, "Tenant context changed");
                  return tenants.requestTenantLifecycle(
                      requestId,
                      c.userId(),
                      tenantId,
                      "ACTIVE",
                      "DELETING",
                      material,
                      clock.instant());
                }),
        requestId.toString(),
        tenantId.toString());
  }

  @Override
  public TenantLifecycleMutation restoreTenant(
      UUID requestId, String refreshCredential, UUID tenantId) {
    request(requestId);
    required(tenantId);
    UUID actor = withSession(refreshCredential, false, LockedRefreshCredential::userId);
    authorization.checkPlatformPermission(actor, "platform.tenant.restore");
    return withFingerprint(
        "RESTORE_TENANT",
        material ->
            withSession(
                refreshCredential,
                false,
                c -> {
                  if (!actor.equals(c.userId()))
                    throw error(TenantError.INVALID_SESSION, "Session identity changed");
                  return tenants.restoreTenant(
                      requestId, actor, tenantId, material, clock.instant());
                }),
        requestId.toString(),
        tenantId.toString());
  }

  @Override
  public List<InvitationSummary> listReceivedInvitations(String refreshCredential) {
    return withSession(
        refreshCredential,
        false,
        c -> tenants.listReceivedInvitations(c.userId(), clock.instant()));
  }

  @Override
  public List<InvitationSummary> listTenantInvitations(String refreshCredential) {
    SessionAuthority authority =
        withSession(
            refreshCredential,
            true,
            c -> new SessionAuthority(c.userId(), c.selectedTenantId(), c.selectedMembershipId()));
    authorization.checkPermission(
        authority.tenantId(), authority.membershipId(), "membership.role.assign");
    return withSession(
        refreshCredential,
        true,
        c -> {
          if (!authority.tenantId().equals(c.selectedTenantId())
              || !authority.membershipId().equals(c.selectedMembershipId()))
            throw error(TenantError.INVALID_SESSION, "Tenant context changed");
          return tenants.listTenantInvitations(authority.tenantId(), clock.instant());
        });
  }

  @Override
  public InvitationMutation declineInvitation(
      UUID requestId, String refreshCredential, UUID invitationId) {
    request(requestId);
    required(invitationId);
    return withFingerprint(
        "DECLINE_INVITATION",
        material ->
            withSession(
                refreshCredential,
                false,
                c ->
                    tenants.declineInvitation(
                        requestId, c.userId(), invitationId, material, clock.instant())),
        requestId.toString(),
        invitationId.toString());
  }

  @Override
  public InvitationMutation revokeInvitation(
      UUID requestId, String refreshCredential, UUID invitationId) {
    request(requestId);
    required(invitationId);
    SessionAuthority authority =
        withSession(
            refreshCredential,
            true,
            c -> new SessionAuthority(c.userId(), c.selectedTenantId(), c.selectedMembershipId()));
    authorization.checkPermission(
        authority.tenantId(), authority.membershipId(), "membership.role.assign");
    return withFingerprint(
        "REVOKE_INVITATION",
        material ->
            withSession(
                refreshCredential,
                true,
                c -> {
                  if (!authority.tenantId().equals(c.selectedTenantId())
                      || !authority.membershipId().equals(c.selectedMembershipId()))
                    throw error(TenantError.INVALID_SESSION, "Tenant context changed");
                  return tenants.revokeInvitation(
                      requestId,
                      c.userId(),
                      authority.tenantId(),
                      invitationId,
                      material,
                      clock.instant());
                }),
        requestId.toString(),
        authority.tenantId().toString(),
        invitationId.toString());
  }

  @Override
  public InvitationResult reissueInvitation(
      UUID requestId, String refreshCredential, UUID invitationId) {
    request(requestId);
    required(invitationId);
    SessionAuthority authority =
        withSession(
            refreshCredential,
            true,
            c -> new SessionAuthority(c.userId(), c.selectedTenantId(), c.selectedMembershipId()));
    authorization.checkPermission(
        authority.tenantId(), authority.membershipId(), "membership.role.assign");
    return withFingerprint(
        "REISSUE_INVITATION",
        material ->
            withSession(
                refreshCredential,
                true,
                c -> {
                  if (!authority.tenantId().equals(c.selectedTenantId())
                      || !authority.membershipId().equals(c.selectedMembershipId()))
                    throw error(TenantError.INVALID_SESSION, "Tenant context changed");
                  Instant now = clock.instant();
                  return tenants.reissueInvitation(
                      requestId,
                      c.userId(),
                      authority.tenantId(),
                      invitationId,
                      material,
                      now,
                      now.plus(INVITATION_TTL));
                }),
        requestId.toString(),
        authority.tenantId().toString(),
        invitationId.toString());
  }

  private TenantLifecycleMutation platformLifecycle(
      UUID requestId,
      String refreshCredential,
      UUID tenantId,
      String permission,
      String operation,
      String expectedLifecycle,
      String targetLifecycle) {
    request(requestId);
    UUID actor = withSession(refreshCredential, false, LockedRefreshCredential::userId);
    authorization.checkPlatformPermission(actor, permission);
    return withFingerprint(
        operation,
        material ->
            withSession(
                refreshCredential,
                false,
                c -> {
                  if (!actor.equals(c.userId()))
                    throw error(TenantError.INVALID_SESSION, "Session identity changed");
                  return tenants.requestTenantLifecycle(
                      requestId,
                      actor,
                      tenantId,
                      expectedLifecycle,
                      targetLifecycle,
                      material,
                      clock.instant());
                }),
        requestId.toString(),
        tenantId.toString(),
        targetLifecycle);
  }

  private <T> T withSession(
      String encoded, boolean tenantRequired, Function<LockedRefreshCredential, T> work) {
    SessionResult<T> result =
        transactions.required(
            () -> {
              Instant now = clock.instant();
              LockedRefreshCredential current = lookup.lock(authStore, encoded).orElse(null);
              if (current == null || !"ACTIVE".equals(current.familyState()))
                return SessionResult.invalid();
              if (!"ACTIVE".equals(current.credentialState())) {
                authStore.revokeFamily(
                    current.refreshFamilyId(), RefreshFamilyRevocationReason.REFRESH_REUSE, now);
                return SessionResult.reuse();
              }
              if (!"ACTIVE".equals(current.userStatus())
                  || !now.isBefore(current.idleExpiresAt())
                  || !now.isBefore(current.absoluteExpiresAt())) {
                authStore.revokeFamily(
                    current.refreshFamilyId(), RefreshFamilyRevocationReason.EXPIRED, now);
                return SessionResult.invalid();
              }
              if (tenantRequired
                  && (current.sessionMode() != AuthenticationSessionMode.TENANT_AUTHENTICATED
                      || current.selectedTenantId() == null
                      || current.selectedMembershipId() == null
                      || !tenants.isSelectable(
                          current.userId(),
                          current.selectedTenantId(),
                          current.selectedMembershipId()))) return SessionResult.invalid();
              return SessionResult.success(work.apply(current));
            });
    if (result.kind == SessionKind.REUSE)
      throw error(TenantError.INVALID_SESSION, "Refresh reuse detected");
    if (result.kind != SessionKind.SUCCESS)
      throw error(TenantError.INVALID_SESSION, "Session is invalid");
    return result.value;
  }

  private <T> T withFingerprint(String operation, Function<byte[], T> action, String... values) {
    byte[] material = encoder.encode(operation, values);
    try {
      return action.apply(material);
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  private static Name name(String input) {
    if (input == null) throw invalid();
    String v = Normalizer.normalize(input.trim(), Normalizer.Form.NFC);
    if (v.isEmpty()
        || v.codePointCount(0, v.length()) > 120
        || v.codePoints().anyMatch(Character::isISOControl)) throw invalid();
    return new Name(v);
  }

  private static String slug(String input) {
    if (input == null) throw invalid();
    String v = input.trim().toLowerCase(Locale.ROOT);
    if (!SLUG.matcher(v).matches() || RESERVED.contains(v)) throw invalid();
    return v;
  }

  private static void request(UUID v) {
    if (v == null || v.version() != 4) throw invalid();
  }

  private static <T> T required(T v) {
    if (v == null) throw invalid();
    return v;
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static TenantException invalid() {
    return error(TenantError.INVALID_ARGUMENT, "Invalid tenant argument");
  }

  private static TenantException error(TenantError e, String m) {
    return new TenantException(e, m);
  }

  private record Name(String value) {}

  private record SessionAuthority(UUID userId, UUID tenantId, UUID membershipId) {}

  private enum SessionKind {
    SUCCESS,
    INVALID,
    REUSE
  }

  private static final class SessionResult<T> {
    final SessionKind kind;
    final T value;

    private SessionResult(SessionKind k, T v) {
      kind = k;
      value = v;
    }

    static <T> SessionResult<T> success(T v) {
      return new SessionResult<>(SessionKind.SUCCESS, v);
    }

    static <T> SessionResult<T> invalid() {
      return new SessionResult<>(SessionKind.INVALID, null);
    }

    static <T> SessionResult<T> reuse() {
      return new SessionResult<>(SessionKind.REUSE, null);
    }
  }
}
