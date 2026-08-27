package com.sajtech.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.TenantError;
import com.sajtech.identity.application.tenant.model.*;
import com.sajtech.identity.infrastructure.observability.AuthorizationOutboxMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@org.junit.jupiter.api.Tag("integration")
class IdentityTenantPersistenceIntegrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
          .asCompatibleSubstituteFor("postgres");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("identity")
          .withUsername("identity_test")
          .withPassword("identity_test_password");
  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
  private DSLContext dsl;
  private SpringTransactionRunner tx;
  private JooqTenantStore tenantStore;
  private JooqAuthenticationStore authStore;
  private UUID ownerUser, targetUser;

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void reset() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway flyway =
        Flyway.configure()
            .dataSource(source)
            .cleanDisabled(false)
            .locations("classpath:db/migration")
            .load();
    flyway.clean();
    flyway.migrate();
    dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    tx = new SpringTransactionRunner(new DataSourceTransactionManager(source));
    IntentFingerprintPort fingerprints = mock(IntentFingerprintPort.class);
    when(fingerprints.digest(any(byte[].class))).thenAnswer(i -> fingerprint(i.getArgument(0)));
    when(fingerprints.matches(any(byte[].class), any())).thenReturn(true);
    tenantStore = new JooqTenantStore(dsl, fingerprints);
    authStore = new JooqAuthenticationStore(dsl);
    ownerUser = UUID.randomUUID();
    targetUser = UUID.randomUUID();
    insertUser(ownerUser);
    insertUser(targetUser);
  }

  @Test
  void tenantActivationSelectionInvitationAndRemovalUseDurableProjectionAndOutboxState() {
    UUID createRequest = UUID.randomUUID();
    TenantCreation created =
        tx.required(
            () ->
                tenantStore.createTenant(
                    createRequest, ownerUser, "Acme", "acme", material(1), NOW));
    assertThat(
            dsl.fetchValue(
                "SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", created.tenantId()))
        .isEqualTo("PROVISIONING");
    AuthorizationOutboxItem ownerOutbox =
        tx.required(() -> tenantStore.claimAuthorizationOutbox(NOW, 32, NOW.plusSeconds(30)))
            .getFirst();
    assertThat(ownerOutbox.operation()).isEqualTo("PROVISION_OWNER");
    tx.required(
        () -> {
          tenantStore.completeAuthorizationOutbox(ownerOutbox.outboxId(), NOW.plusSeconds(1));
          return null;
        });
    assertThat(tx.required(() -> tenantStore.listSelectable(ownerUser)))
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.tenantId()).isEqualTo(created.tenantId());
              assertThat(t.membershipId()).isEqualTo(created.membershipId());
            });

    RefreshDigest oldDigest = refresh((byte) 1), nextDigest = refresh((byte) 2);
    UUID family = UUID.randomUUID();
    PreparedSession prepared =
        new PreparedSession(
            family,
            "s".repeat(43),
            ownerUser,
            UUID.randomUUID(),
            oldDigest,
            NOW,
            NOW,
            NOW.plus(Duration.ofDays(7)),
            NOW.plus(Duration.ofDays(30)));
    tx.required(
        () -> {
          authStore.createSession(prepared);
          return null;
        });
    LockedRefreshCredential current =
        tx.required(() -> authStore.lockRefreshCredential(oldDigest).orElseThrow());
    tx.required(
        () -> {
          tenantStore.selectContext(
              current,
              created.membershipId(),
              created.tenantId(),
              UUID.randomUUID(),
              nextDigest,
              NOW.plusSeconds(2),
              NOW.plus(Duration.ofDays(7)));
          return null;
        });
    LockedRefreshCredential selected =
        tx.required(() -> authStore.lockRefreshCredential(nextDigest).orElseThrow());
    assertThat(selected.sessionMode()).isEqualTo(AuthenticationSessionMode.TENANT_AUTHENTICATED);
    assertThat(selected.selectedTenantId()).isEqualTo(created.tenantId());
    assertThat(selected.selectedMembershipId()).isEqualTo(created.membershipId());
    assertThat(
            tx.required(() -> authStore.lockRefreshCredential(oldDigest).orElseThrow())
                .credentialState())
        .isEqualTo("ROTATED");

    UUID contact = insertVerifiedEmail(targetUser, "target@example.com");
    UUID inviteRequest = UUID.randomUUID();
    InvitationResult invite =
        tx.required(
            () ->
                tenantStore.createInvitation(
                    inviteRequest,
                    ownerUser,
                    created.tenantId(),
                    contact,
                    material(2),
                    NOW.plusSeconds(3),
                    NOW.plus(Duration.ofDays(7))));
    UUID acceptRequest = UUID.randomUUID();
    AcceptedInvitation accepted =
        tx.required(
            () ->
                tenantStore.acceptInvitation(
                    acceptRequest,
                    targetUser,
                    invite.invitationId(),
                    material(3),
                    NOW.plusSeconds(4)));
    assertThat(accepted.tenantId()).isEqualTo(created.tenantId());
    List<AuthorizationOutboxItem> pending =
        tx.required(
            () ->
                tenantStore.claimAuthorizationOutbox(NOW.plusSeconds(4), 32, NOW.plusSeconds(34)));
    assertThat(pending)
        .anySatisfy(item -> assertThat(item.operation()).isEqualTo("PROVISION_MEMBER"));

    UUID removeRequest = UUID.randomUUID();
    RemovalPreparation removal =
        tx.required(
            () ->
                tenantStore.createRemovalIntent(
                    removeRequest,
                    ownerUser,
                    created.tenantId(),
                    created.membershipId(),
                    accepted.membershipId(),
                    material(4),
                    NOW.plusSeconds(5)));
    assertThat(removal.targetMembershipId()).isEqualTo(accepted.membershipId());
    tx.required(
        () -> {
          tenantStore.commitMembershipRemoval(removeRequest, NOW.plusSeconds(6));
          return null;
        });
    assertThat(
            dsl.fetchValue(
                "SELECT membership_lifecycle FROM identity_user_membership_query WHERE membership_id=?",
                accepted.membershipId()))
        .isEqualTo("REMOVED");
    assertThat(
            tx.required(
                () ->
                    tenantStore.claimAuthorizationOutbox(
                        NOW.plusSeconds(6), 32, NOW.plusSeconds(36))))
        .anySatisfy(item -> assertThat(item.operation()).isEqualTo("FINALIZE_MEMBERSHIP_REMOVAL"));
  }

  @Test
  void authorizationOutboxMetricsExposeIndexedOldestAgeAndBoundedDefinitiveFailures() {
    UUID createRequest = UUID.randomUUID();
    tx.required(
        () ->
            tenantStore.createTenant(
                createRequest, ownerUser, "Metrics Tenant", "metrics-tenant", material(7), NOW));

    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    AuthorizationOutboxMetrics metrics = new AuthorizationOutboxMetrics(meters);
    Instant metricNow = NOW.plusSeconds(901);
    assertThat(metrics.sampleDue(metricNow)).isTrue();
    metrics.recordOldestPending(
        tenantStore.oldestUnresolvedAuthorizationOutboxCreatedAt().orElse(null), metricNow);
    metrics.definitiveFailure("PROVISION_OWNER");
    metrics.definitiveFailure("UNRECOGNIZED_OPERATION");

    assertThat(meters.get("identity.authorization.outbox.oldest_pending_age").gauge().value())
        .isEqualTo(901);
    assertThat(
            meters
                .get("identity.authorization.outbox.definitive_failures")
                .tag("operation", "PROVISION_OWNER")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meters
                .get("identity.authorization.outbox.definitive_failures")
                .tag("operation", "UNKNOWN")
                .counter()
                .count())
        .isEqualTo(1);

    AuthorizationOutboxItem item =
        tx.required(
                () ->
                    tenantStore.claimAuthorizationOutbox(
                        NOW.plusSeconds(901), 32, NOW.plusSeconds(931)))
            .getFirst();
    tx.required(
        () -> {
          tenantStore.completeAuthorizationOutbox(item.outboxId(), NOW.plusSeconds(902));
          return null;
        });

    SimpleMeterRegistry resolvedMeters = new SimpleMeterRegistry();
    AuthorizationOutboxMetrics resolved = new AuthorizationOutboxMetrics(resolvedMeters);
    Instant resolvedNow = NOW.plusSeconds(903);
    assertThat(resolved.sampleDue(resolvedNow)).isTrue();
    resolved.recordOldestPending(
        tenantStore.oldestUnresolvedAuthorizationOutboxCreatedAt().orElse(null), resolvedNow);
    assertThat(
            resolvedMeters.get("identity.authorization.outbox.oldest_pending_age").gauge().value())
        .isZero();

    String indexDefinition =
        java.util.Objects.requireNonNull(
            (String)
                dsl.fetchValue(
                    "SELECT indexdef FROM pg_indexes WHERE indexname='identity_authorization_outbox_oldest_unresolved_idx'"));
    assertThat(indexDefinition)
        .contains("(created_at, outbox_id)")
        .contains("PENDING")
        .contains("DISPATCHING");

    String plan =
        String.join(
            System.lineSeparator(),
            dsl.fetch(
                    """
                    EXPLAIN (COSTS OFF)
                    SELECT created_at
                    FROM identity_authorization_outbox
                    WHERE state IN ('PENDING','DISPATCHING')
                    ORDER BY created_at, outbox_id
                    LIMIT 1
                    """)
                .getValues(0, String.class));
    assertThat(plan).contains("identity_authorization_outbox_oldest_unresolved_idx");
  }

  @Test
  void deleteAndRestoreFinalizeOnlyAfterOrderedAuthorizationAcknowledgements() {
    TenantCreation created = activeTenant("Lifecycle Tenant", "lifecycle-tenant", 10);
    UUID contact = insertVerifiedEmail(targetUser, "lifecycle-target@example.com");
    InvitationResult invitation =
        tx.required(
            () ->
                tenantStore.createInvitation(
                    UUID.randomUUID(),
                    ownerUser,
                    created.tenantId(),
                    contact,
                    material(11),
                    NOW.plusSeconds(2),
                    NOW.plus(Duration.ofDays(7))));

    UUID deleteRequest = UUID.randomUUID();
    TenantLifecycleMutation accepted =
        tx.required(
            () ->
                tenantStore.requestTenantLifecycle(
                    deleteRequest,
                    ownerUser,
                    created.tenantId(),
                    "ACTIVE",
                    "DELETING",
                    material(12),
                    NOW.plusSeconds(3)));
    assertThat(accepted.lifecycle()).isEqualTo("ACTIVE");
    assertThat(accepted.targetLifecycle()).isEqualTo("DELETED");
    assertThat(accepted.pending()).isTrue();
    assertThat(
            tx.required(
                () ->
                    tenantStore.requestTenantLifecycle(
                        deleteRequest,
                        ownerUser,
                        created.tenantId(),
                        "ACTIVE",
                        "DELETING",
                        material(12),
                        NOW.plusSeconds(3))))
        .isEqualTo(accepted);
    assertThatThrownBy(
            () ->
                tx.required(
                    () ->
                        tenantStore.requestTenantLifecycle(
                            UUID.randomUUID(),
                            ownerUser,
                            created.tenantId(),
                            "ACTIVE",
                            "SUSPENDED",
                            material(13),
                            NOW.plusSeconds(3))))
        .isInstanceOfSatisfying(
            com.sajtech.identity.application.tenant.TenantException.class,
            failure -> assertThat(failure.error()).isEqualTo(TenantError.TENANT_LIFECYCLE_PENDING));

    AuthorizationOutboxItem deleting = claimOne(NOW.plusSeconds(3));
    assertThat(deleting.lifecycle()).isEqualTo("DELETING");
    complete(deleting, NOW.plusSeconds(4));
    assertThat(
            dsl.fetchValue(
                "SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", created.tenantId()))
        .isEqualTo("DELETING");
    assertThat(
            dsl.fetchValue(
                "SELECT state FROM identity_invitation_query WHERE invitation_id=?",
                invitation.invitationId()))
        .isEqualTo("REVOKED");

    AuthorizationOutboxItem deleted = claimOne(NOW.plusSeconds(4));
    assertThat(deleted.lifecycle()).isEqualTo("DELETED");
    complete(deleted, NOW.plusSeconds(5));
    assertThat(
            dsl.fetchValue(
                "SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", created.tenantId()))
        .isEqualTo("DELETED");

    TenantLifecycleMutation restoring =
        tx.required(
            () ->
                tenantStore.restoreTenant(
                    UUID.randomUUID(),
                    ownerUser,
                    created.tenantId(),
                    material(14),
                    NOW.plusSeconds(6)));
    assertThat(restoring.lifecycle()).isEqualTo("PROVISIONING");
    assertThat(restoring.targetLifecycle()).isEqualTo("ACTIVE");
    complete(claimOne(NOW.plusSeconds(6)), NOW.plusSeconds(7));
    assertThat(
            dsl.fetchValue(
                "SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", created.tenantId()))
        .isEqualTo("ACTIVE");

    dsl.execute(
        "UPDATE identity_tenant SET lifecycle='DELETED',purge_started_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=?",
        OffsetDateTime.ofInstant(NOW.plusSeconds(8), ZoneOffset.UTC),
        created.tenantId());
    assertThatThrownBy(
            () ->
                tx.required(
                    () ->
                        tenantStore.restoreTenant(
                            UUID.randomUUID(),
                            ownerUser,
                            created.tenantId(),
                            material(15),
                            NOW.plusSeconds(9))))
        .isInstanceOfSatisfying(
            com.sajtech.identity.application.tenant.TenantException.class,
            failure -> assertThat(failure.error()).isEqualTo(TenantError.TENANT_RESTORE_FORBIDDEN));
  }

  @Test
  void invitationsDeclineReissueExpireAndRevokeWithFreshTtl() {
    TenantCreation created = activeTenant("Invitation Tenant", "invitation-tenant", 20);
    UUID contact = insertVerifiedEmail(targetUser, "invitation-target@example.com");
    InvitationResult original =
        tx.required(
            () ->
                tenantStore.createInvitation(
                    UUID.randomUUID(),
                    ownerUser,
                    created.tenantId(),
                    contact,
                    material(21),
                    NOW.plusSeconds(2),
                    NOW.plus(Duration.ofDays(7))));
    InvitationMutation declined =
        tx.required(
            () ->
                tenantStore.declineInvitation(
                    UUID.randomUUID(),
                    targetUser,
                    original.invitationId(),
                    material(22),
                    NOW.plusSeconds(3)));
    assertThat(declined.state()).isEqualTo("DECLINED");

    Instant reissuedAt = NOW.plusSeconds(4);
    InvitationResult reissued =
        tx.required(
            () ->
                tenantStore.reissueInvitation(
                    UUID.randomUUID(),
                    ownerUser,
                    created.tenantId(),
                    original.invitationId(),
                    material(23),
                    reissuedAt,
                    reissuedAt.plus(Duration.ofDays(7))));
    assertThat(reissued.expiresAt()).isEqualTo(reissuedAt.plus(Duration.ofDays(7)));
    assertThat(
            dsl.fetchValue(
                "SELECT reissued_from_invitation_id FROM identity_tenant_invitation WHERE invitation_id=?",
                reissued.invitationId()))
        .isEqualTo(original.invitationId());

    Instant afterExpiry = reissuedAt.plus(Duration.ofDays(7));
    assertThat(tx.required(() -> tenantStore.expireInvitations(afterExpiry, 200))).isEqualTo(1);
    assertThat(tx.required(() -> tenantStore.listReceivedInvitations(targetUser, afterExpiry)))
        .filteredOn(invitation -> invitation.invitationId().equals(reissued.invitationId()))
        .singleElement()
        .satisfies(invitation -> assertThat(invitation.state()).isEqualTo("EXPIRED"));

    InvitationResult second =
        tx.required(
            () ->
                tenantStore.reissueInvitation(
                    UUID.randomUUID(),
                    ownerUser,
                    created.tenantId(),
                    reissued.invitationId(),
                    material(24),
                    afterExpiry.plusSeconds(1),
                    afterExpiry.plus(Duration.ofDays(7))));
    InvitationMutation revoked =
        tx.required(
            () ->
                tenantStore.revokeInvitation(
                    UUID.randomUUID(),
                    ownerUser,
                    created.tenantId(),
                    second.invitationId(),
                    material(25),
                    afterExpiry.plusSeconds(2)));
    assertThat(revoked.state()).isEqualTo("REVOKED");
  }

  private TenantCreation activeTenant(String name, String slug, int marker) {
    TenantCreation created =
        tx.required(
            () ->
                tenantStore.createTenant(
                    UUID.randomUUID(), ownerUser, name, slug, material(marker), NOW));
    AuthorizationOutboxItem owner = claimOne(NOW);
    complete(owner, NOW.plusSeconds(1));
    return created;
  }

  private AuthorizationOutboxItem claimOne(Instant now) {
    return tx.required(() -> tenantStore.claimAuthorizationOutbox(now, 32, now.plusSeconds(30)))
        .getFirst();
  }

  private void complete(AuthorizationOutboxItem item, Instant now) {
    tx.required(
        () -> {
          tenantStore.completeAuthorizationOutbox(item.outboxId(), now);
          return null;
        });
  }

  private void insertUser(UUID user) {
    dsl.execute(
        "INSERT INTO identity_user(user_id,status,created_at,updated_at) VALUES (?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        user,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
  }

  private UUID insertVerifiedEmail(UUID user, String email) {
    UUID contact = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO identity_contact(contact_id,user_id,contact_type,canonical_value,delivery_value,verified_at,primary_active,created_at,updated_at) VALUES (?,?, 'EMAIL',?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),TRUE,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        contact,
        user,
        email,
        email,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return contact;
  }

  private static RefreshDigest refresh(byte marker) {
    byte[] b = new byte[32];
    b[0] = marker;
    return new RefreshDigest("k1", "refresh-hmac-v1", b);
  }

  private static FingerprintDigest fingerprint(byte[] material) {
    byte[] b = new byte[32];
    System.arraycopy(material, 0, b, 0, Math.min(material.length, b.length));
    return new FingerprintDigest(b, "identity-fingerprint-v1", "k1");
  }

  private static byte[] material(int marker) {
    byte[] b = new byte[8];
    Arrays.fill(b, (byte) marker);
    return b;
  }
}
