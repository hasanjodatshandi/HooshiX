package com.sajtech.authorization.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@org.junit.jupiter.api.Tag("integration")
class AuthorizationPersistenceIntegrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
          .asCompatibleSubstituteFor("postgres");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("authorization")
          .withUsername("authorization_test")
          .withPassword("authorization_test_password");
  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
  private DSLContext admin, runtime;
  private JooqAuthorizationStore store;

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
    admin = DSL.using(source, SQLDialect.POSTGRES);
    if (admin.fetchCount(
            DSL.selectOne()
                .from("pg_roles")
                .where(DSL.field("rolname").eq("authorization_runtime_test")))
        == 0)
      admin.execute(
          "CREATE ROLE authorization_runtime_test LOGIN PASSWORD 'runtime_test_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
    admin.execute("GRANT USAGE ON SCHEMA public TO authorization_runtime_test");
    admin.execute(
        "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO authorization_runtime_test");
    DriverManagerDataSource runtimeSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), "authorization_runtime_test", "runtime_test_password");
    runtime = DSL.using(new TransactionAwareDataSourceProxy(runtimeSource), SQLDialect.POSTGRES);
    store = new JooqAuthorizationStore(runtime);
    store.projectPermissionCatalog(catalog(), 1, NOW);
  }

  @Test
  void permissionPrecedenceAndCrossTenantAreDefaultDeny() {
    UUID t1 = UUID.randomUUID(),
        ownerMembership = UUID.randomUUID(),
        ownerUser = UUID.randomUUID(),
        member = UUID.randomUUID(),
        memberUser = UUID.randomUUID();
    store.provisionOwner(UUID.randomUUID(), digest(1), t1, ownerMembership, ownerUser, NOW);
    store.provisionMember(UUID.randomUUID(), digest(2), t1, member, memberUser, NOW);
    assertThat(store.checkPermission(t1, member, "tenant.read")).isTrue();
    assertThat(store.checkPermission(t1, member, "tenant.delete")).isFalse();
    ActorContext owner = new ActorContext(ownerUser, t1, ownerMembership, "s".repeat(43));
    store.setOverride(
        owner,
        UUID.randomUUID(),
        digest(3),
        member,
        "tenant.delete",
        "GRANT",
        "temporary grant",
        NOW);
    assertThat(store.checkPermission(t1, member, "tenant.delete")).isTrue();
    store.setOverride(
        owner, UUID.randomUUID(), digest(4), member, "tenant.read", "DENY", "explicit deny", NOW);
    assertThat(store.checkPermission(t1, member, "tenant.read")).isFalse();
    UUID t2 = UUID.randomUUID();
    store.provisionOwner(
        UUID.randomUUID(), digest(5), t2, UUID.randomUUID(), UUID.randomUUID(), NOW);
    assertThat(store.checkPermission(t2, member, "tenant.read")).isFalse();
  }

  @Test
  void forcedRlsHidesOtherTenantRowsFromRuntimeRole() {
    UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID();
    store.provisionOwner(
        UUID.randomUUID(), digest(1), t1, UUID.randomUUID(), UUID.randomUUID(), NOW);
    store.provisionOwner(
        UUID.randomUUID(), digest(2), t2, UUID.randomUUID(), UUID.randomUUID(), NOW);
    Integer count =
        runtime.transactionResult(
            c -> {
              DSLContext tx = DSL.using(c);
              tx.fetchValue("SELECT set_config('app.current_tenant_id', ?, true)", t1.toString());
              Object v =
                  tx.fetchValue("SELECT count(*) FROM authorization_role WHERE tenant_id=?", t2);
              return ((Number) v).intValue();
            });
    assertThat(count).isZero();
  }

  @Test
  void equalReplaySucceedsAndConflictingReplayFails() {
    UUID request = UUID.randomUUID(),
        tenant = UUID.randomUUID(),
        membership = UUID.randomUUID(),
        user = UUID.randomUUID();
    FingerprintDigest one = digest(1);
    store.provisionOwner(request, one, tenant, membership, user, NOW);
    assertThatCode(
            () -> store.provisionOwner(request, one, tenant, membership, user, NOW.plusSeconds(1)))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                store.provisionOwner(
                    request, digest(9), tenant, membership, user, NOW.plusSeconds(2)))
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            e -> assertThat(e.error()).isEqualTo(AuthorizationError.REQUEST_ID_CONFLICT));
  }

  @Test
  void concurrentOwnerRemovalPreparationsCannotReserveAllOwners() throws Exception {
    UUID tenant = UUID.randomUUID(),
        owner1 = UUID.randomUUID(),
        user1 = UUID.randomUUID(),
        owner2 = UUID.randomUUID(),
        user2 = UUID.randomUUID();
    store.provisionOwner(UUID.randomUUID(), digest(1), tenant, owner1, user1, NOW);
    store.provisionMember(UUID.randomUUID(), digest(2), tenant, owner2, user2, NOW);
    ActorContext actor = new ActorContext(user1, tenant, owner1, "s".repeat(43));
    UUID ownerRole =
        store.listRoles(actor, 50, null).stream()
            .filter(r -> r.name().equals("tenant_owner"))
            .findFirst()
            .orElseThrow()
            .roleId();
    store.assignRole(actor, UUID.randomUUID(), digest(3), owner2, ownerRole, "promote owner", NOW);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
    List<Future<AuthorizationError>> results = new ArrayList<>();
    for (UUID membership : List.of(owner1, owner2)) {
      results.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  store.prepareMembershipRemoval(
                      UUID.randomUUID(),
                      digest((int) (membership.getLeastSignificantBits() & 0x7f)),
                      tenant,
                      membership,
                      NOW);
                  return null;
                } catch (AuthorizationException e) {
                  return e.error();
                }
              }));
    }
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    go.countDown();
    List<AuthorizationError> errors = new ArrayList<>();
    errors.add(results.get(0).get(5, TimeUnit.SECONDS));
    errors.add(results.get(1).get(5, TimeUnit.SECONDS));
    pool.shutdownNow();
    assertThat(errors).containsExactlyInAnyOrder(null, AuthorizationError.LAST_TENANT_OWNER);
  }

  @Test
  void preparedMembershipRemovalBlocksConflictingOwnerRoleMutation() {
    UUID tenant = UUID.randomUUID(),
        owner1 = UUID.randomUUID(),
        user1 = UUID.randomUUID(),
        owner2 = UUID.randomUUID(),
        user2 = UUID.randomUUID();
    store.provisionOwner(UUID.randomUUID(), digest(1), tenant, owner1, user1, NOW);
    store.provisionMember(UUID.randomUUID(), digest(2), tenant, owner2, user2, NOW);
    ActorContext actor = new ActorContext(user1, tenant, owner1, "s".repeat(43));
    UUID ownerRole =
        store.listRoles(actor, 50, null).stream()
            .filter(r -> r.name().equals("tenant_owner"))
            .findFirst()
            .orElseThrow()
            .roleId();
    store.assignRole(actor, UUID.randomUUID(), digest(3), owner2, ownerRole, "promote owner", NOW);
    store.prepareMembershipRemoval(
        UUID.randomUUID(), digest(4), tenant, owner2, NOW.plusSeconds(1));

    assertThatThrownBy(
            () ->
                store.removeRole(
                    actor,
                    UUID.randomUUID(),
                    digest(5),
                    owner2,
                    ownerRole,
                    "demote reserved owner",
                    NOW.plusSeconds(2)))
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            e -> assertThat(e.error()).isEqualTo(AuthorizationError.LAST_TENANT_OWNER));
  }

  private static FingerprintDigest digest(int seed) {
    byte[] value = new byte[32];
    Arrays.fill(value, (byte) seed);
    return new FingerprintDigest("authorization-intent-fingerprint-v1", "k1", Map.of("k1", value));
  }

  private static List<PermissionModel> catalog() {
    return List.of(
            "tenant.read",
            "tenant.delete",
            "role.read",
            "role.create",
            "role.update",
            "role.archive",
            "role.permission.manage",
            "membership.read",
            "membership.role.assign",
            "membership.permission.manage",
            "membership.owner.assign")
        .stream()
        .map(k -> new PermissionModel(k, "TENANT", "ACTIVE"))
        .toList();
  }
}
