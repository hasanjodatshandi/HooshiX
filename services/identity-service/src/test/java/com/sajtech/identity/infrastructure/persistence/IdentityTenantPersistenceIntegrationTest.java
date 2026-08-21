package com.sajtech.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.model.*;
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
