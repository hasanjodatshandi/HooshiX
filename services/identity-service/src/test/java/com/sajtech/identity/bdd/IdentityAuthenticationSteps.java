package com.sajtech.identity.bdd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.authentication.usecase.AuthenticateLocalUseCase;
import com.sajtech.identity.application.authentication.usecase.IssueAudienceAccessTokenUseCase;
import com.sajtech.identity.application.authentication.usecase.RefreshSessionUseCase;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.registration.service.PasswordNormalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class IdentityAuthenticationSteps {
  private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
  private final FakeStore store = new FakeStore();
  private final FakeCredentials credentials = new FakeCredentials();
  private AuthenticationSession session;
  private Throwable tokenFailure;
  private Throwable refreshFailure;

  @Given("an active verified local account with a valid password")
  public void activeVerifiedLocalAccount() {
    store.local = new LocalCredentialRecord(UUID.randomUUID(), "ACTIVE", "$argon2id$stored");
  }

  @When("local password authentication is requested")
  public void authenticateLocal() {
    AuthenticateLocalUseCase useCase =
        new AuthenticateLocalUseCase(
            new ContactCanonicalizer(),
            new PasswordNormalizer(),
            new NoopQuota(),
            (password, hash) -> "correct password".equals(password) && hash != null,
            credentials,
            new DirectTransactionRunner(),
            store,
            Clock.fixed(NOW, ZoneOffset.UTC));
    session =
        useCase.authenticate(
            new AuthenticateLocalCommand(
                UUID.randomUUID(),
                RegistrationChannel.EMAIL,
                "Person@Example.com",
                "correct password",
                new byte[] {127, 0, 0, 1}));
  }

  @Then("an authenticated onboarding session is created")
  public void onboardingSessionCreated() {
    assertThat(session).isNotNull();
    assertThat(session.mode()).isEqualTo(AuthenticationSessionMode.AUTHENTICATED_ONBOARDING);
    assertThat(store.created).isNotNull();
  }

  @Then("the refresh idle lifetime is 7 days and the absolute lifetime is 30 days")
  public void exactSessionLifetimes() {
    assertThat(session.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    assertThat(session.absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
  }

  @Then("ordinary audience token issuance requires tenant selection")
  public void tokenRequiresTenantSelection() {
    store.current = activeLocked(store.created);
    IssueAudienceAccessTokenUseCase useCase =
        new IssueAudienceAccessTokenUseCase(
            Set.of("authorization-service"),
            new RefreshCredentialLookup(credentials),
            new DirectTransactionRunner(),
            store,
            (userId, tenantId, membershipId) -> true,
            context -> new SignedAccessToken("test-token", NOW.plusSeconds(300)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    tokenFailure =
        catchThrowable(
            () ->
                useCase.issue(
                    new IssueAudienceAccessTokenCommand(
                        UUID.randomUUID(), session.refreshCredential(), "authorization-service")));
    assertThat(tokenFailure)
        .isInstanceOfSatisfying(
            AuthenticationException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(AuthenticationError.TENANT_SELECTION_REQUIRED));
  }

  @Given("an active onboarding refresh family with a rotated predecessor")
  public void rotatedPredecessor() {
    store.current =
        new LockedRefreshCredential(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "s".repeat(43),
            UUID.randomUUID(),
            "ROTATED",
            "ACTIVE",
            "ACTIVE",
            AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
            NOW.plus(Duration.ofDays(1)),
            NOW.plus(Duration.ofDays(30)));
  }

  @When("the rotated predecessor is used to refresh the session")
  public void reuseRotatedPredecessor() {
    RefreshSessionUseCase useCase =
        new RefreshSessionUseCase(
            new RefreshCredentialLookup(credentials),
            credentials,
            new DirectTransactionRunner(),
            store,
            Clock.fixed(NOW, ZoneOffset.UTC));
    refreshFailure =
        catchThrowable(
            () ->
                useCase.refresh(new RefreshSessionCommand(UUID.randomUUID(), FakeCredentials.RAW)));
  }

  @Then("refresh is rejected as credential reuse")
  public void refreshRejectedAsReuse() {
    assertThat(refreshFailure)
        .isInstanceOfSatisfying(
            AuthenticationException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(AuthenticationError.REFRESH_REUSE_DETECTED));
  }

  @Then("the complete refresh family is revoked for reuse")
  public void familyRevokedForReuse() {
    assertThat(store.revokedFamily).isEqualTo(store.current.refreshFamilyId());
    assertThat(store.revocationReason).isEqualTo(RefreshFamilyRevocationReason.REFRESH_REUSE);
  }

  private static LockedRefreshCredential activeLocked(PreparedSession prepared) {
    return new LockedRefreshCredential(
        prepared.credentialId(),
        prepared.refreshFamilyId(),
        prepared.sessionId(),
        prepared.userId(),
        "ACTIVE",
        "ACTIVE",
        "ACTIVE",
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
        prepared.idleExpiresAt(),
        prepared.absoluteExpiresAt());
  }

  private static final class NoopQuota implements LoginQuotaPort {
    @Override
    public void checkSource(byte[] clientAddress) {}

    @Override
    public void recordFailure(CanonicalContact contact) {}

    @Override
    public void recordSuccess(CanonicalContact contact) {}
  }

  private static final class DirectTransactionRunner implements TransactionRunner {
    @Override
    public <T> T required(java.util.function.Supplier<T> work) {
      return work.get();
    }
  }

  private static final class FakeCredentials implements SessionCredentialPort {
    private static final String RAW = "r".repeat(43);
    private static final RefreshDigest DIGEST =
        new RefreshDigest("k1", "refresh-hmac-v1", new byte[32]);

    @Override
    public String newSessionId() {
      return "s".repeat(43);
    }

    @Override
    public GeneratedRefreshCredential newRefreshCredential() {
      return new GeneratedRefreshCredential(RAW, DIGEST);
    }

    @Override
    public List<RefreshDigest> digestCandidates(String encodedCredential) {
      if (!RAW.equals(encodedCredential)) throw new IllegalArgumentException();
      return List.of(DIGEST);
    }
  }

  private static final class FakeStore implements AuthenticationStore {
    private LocalCredentialRecord local;
    private PreparedSession created;
    private LockedRefreshCredential current;
    private UUID revokedFamily;
    private RefreshFamilyRevocationReason revocationReason;

    @Override
    public Optional<LocalCredentialRecord> findVerifiedLocalCredential(CanonicalContact contact) {
      return Optional.ofNullable(local);
    }

    @Override
    public Optional<LocalCredentialRecord> lockVerifiedLocalCredential(
        UUID userId, CanonicalContact contact) {
      return Optional.ofNullable(local);
    }

    @Override
    public void expireDueFamilies(UUID userId, Instant now) {}

    @Override
    public int countActiveFamilies(UUID userId) {
      return 0;
    }

    @Override
    public Optional<UUID> oldestActiveFamily(UUID userId) {
      return Optional.empty();
    }

    @Override
    public void createSession(PreparedSession prepared) {
      created = prepared;
    }

    @Override
    public Optional<LockedRefreshCredential> lockRefreshCredential(RefreshDigest digest) {
      return Optional.ofNullable(current);
    }

    @Override
    public void rotateRefresh(
        LockedRefreshCredential current,
        UUID newCredentialId,
        RefreshDigest nextDigest,
        Instant now,
        Instant nextIdleExpiresAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void revokeFamily(
        UUID refreshFamilyId, RefreshFamilyRevocationReason reason, Instant now) {
      revokedFamily = refreshFamilyId;
      revocationReason = reason;
    }

    @Override
    public void revokeAllFamilies(UUID userId, RefreshFamilyRevocationReason reason, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int deleteFamiliesBefore(Instant cutoff, int batch) {
      return 0;
    }
  }
}
