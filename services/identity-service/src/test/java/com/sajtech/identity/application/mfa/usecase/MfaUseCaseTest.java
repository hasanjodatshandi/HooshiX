package com.sajtech.identity.application.mfa.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.mfa.MfaError;
import com.sajtech.identity.application.mfa.MfaException;
import com.sajtech.identity.application.mfa.model.*;
import com.sajtech.identity.application.mfa.port.in.CompleteMfaAuthenticationCommand;
import com.sajtech.identity.application.mfa.port.out.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MfaUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  private static final String CHALLENGE = "C".repeat(43);
  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final MfaStore mfa = mock(MfaStore.class);
  private final MfaCryptographyPort cryptography = mock(MfaCryptographyPort.class);
  private final AuthenticationStore authentication = mock(AuthenticationStore.class);
  private final TrackingTransactionRunner transactions = new TrackingTransactionRunner();
  private final TrackingQuota quota = new TrackingQuota(transactions);
  private final FixedCredentials credentials = new FixedCredentials();
  private MfaUseCase useCase;
  private MfaStore.LoginChallenge challenge;
  private MfaStore.ActiveEnrollment enrollment;

  @BeforeEach
  void setUp() {
    challenge =
        new MfaStore.LoginChallenge(
            UUID.randomUUID(),
            USER_ID,
            0,
            NOW.minusSeconds(20),
            NOW.plus(Duration.ofMinutes(5)),
            "ACTIVE");
    enrollment =
        new MfaStore.ActiveEnrollment(
            UUID.randomUUID(),
            USER_ID,
            new EncryptedTotpSecret("mfa-k1", new byte[12], new byte[48]),
            null);
    MfaDigest digest = new MfaDigest(new byte[32], "mfa-k1", "mfa-challenge-hmac-v1");
    when(cryptography.challengeDigestCandidates(CHALLENGE)).thenReturn(List.of(digest));
    when(mfa.findLoginChallenge(List.of(digest))).thenReturn(Optional.of(challenge));
    when(mfa.lockLoginChallenge(List.of(digest))).thenReturn(Optional.of(challenge));
    when(mfa.lockActiveEnrollment(USER_ID)).thenReturn(Optional.of(enrollment));
    when(authentication.lockLocalCredential(USER_ID))
        .thenReturn(Optional.of(new LocalCredentialRecord(USER_ID, "ACTIVE", "$argon2id$stored")));
    when(authentication.countActiveFamilies(USER_ID)).thenReturn(0);
    useCase =
        new MfaUseCase(
            mfa,
            cryptography,
            quota,
            authentication,
            new RefreshCredentialLookup(credentials),
            credentials,
            ignored -> AuthenticationTenantSelection.onboarding(),
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void validTotpCompletesChallengeAndCreatesMfaAssuredSessionAtomically() {
    long timestep = NOW.getEpochSecond() / 30;
    when(cryptography.verifyTotp(
            USER_ID, enrollment.enrollmentId(), enrollment.secret(), "123456", NOW))
        .thenReturn(OptionalLong.of(timestep));

    AuthenticationSession result = useCase.complete(command("123456"));

    assertThat(result.mode()).isEqualTo(AuthenticationSessionMode.AUTHENTICATED_ONBOARDING);
    assertThat(result.userId()).isEqualTo(USER_ID);
    assertThat(result.refreshCredential()).isEqualTo("r".repeat(43));
    ArgumentCaptor<PreparedSession> prepared = ArgumentCaptor.forClass(PreparedSession.class);
    verify(authentication).createSession(prepared.capture());
    assertThat(prepared.getValue().mfaAuthenticatedAt()).isEqualTo(NOW);
    verify(mfa).acceptTotp(enrollment.enrollmentId(), timestep, NOW);
    verify(mfa).completeLoginChallenge(challenge.challengeId(), NOW);
    assertThat(quota.sourceCalls).isEqualTo(1);
    assertThat(quota.failureCalls).isZero();
  }

  @Test
  void acceptedTimestepCannotBeReplayedAndFailurePressureRunsAfterTransaction() {
    long timestep = NOW.getEpochSecond() / 30;
    enrollment =
        new MfaStore.ActiveEnrollment(
            enrollment.enrollmentId(), USER_ID, enrollment.secret(), timestep);
    when(mfa.lockActiveEnrollment(USER_ID)).thenReturn(Optional.of(enrollment));
    when(cryptography.verifyTotp(
            USER_ID, enrollment.enrollmentId(), enrollment.secret(), "123456", NOW))
        .thenReturn(OptionalLong.of(timestep));

    assertThatThrownBy(() -> useCase.complete(command("123456")))
        .isInstanceOfSatisfying(
            MfaException.class,
            exception -> assertThat(exception.error()).isEqualTo(MfaError.INVALID_PROOF));

    verify(mfa, never()).acceptTotp(any(), anyLong(), any());
    verify(authentication, never()).createSession(any());
    verify(mfa).recordLoginFailure(challenge.challengeId(), 1, NOW);
    assertThat(quota.sourceCalls).isEqualTo(1);
    assertThat(quota.failureCalls).isEqualTo(1);
  }

  @Test
  void fifthFailedProofExhaustsChallengeWithoutCreatingAnyCredential() {
    challenge =
        new MfaStore.LoginChallenge(
            challenge.challengeId(),
            USER_ID,
            4,
            challenge.primaryAuthenticatedAt(),
            challenge.expiresAt(),
            "ACTIVE");
    when(mfa.findLoginChallenge(anyList())).thenReturn(Optional.of(challenge));
    when(mfa.lockLoginChallenge(anyList())).thenReturn(Optional.of(challenge));
    when(cryptography.verifyTotp(any(), any(), any(), anyString(), any()))
        .thenReturn(OptionalLong.empty());

    assertThatThrownBy(() -> useCase.complete(command("000000"))).isInstanceOf(MfaException.class);

    verify(mfa).recordLoginFailure(challenge.challengeId(), 5, NOW);
    verify(authentication, never()).createSession(any());
  }

  private static CompleteMfaAuthenticationCommand command(String code) {
    return new CompleteMfaAuthenticationCommand(
        UUID.randomUUID(),
        CHALLENGE,
        new MfaProof(MfaProofType.TOTP, code),
        new byte[] {(byte) 203, 0, 113, 10});
  }

  private static final class TrackingTransactionRunner implements TransactionRunner {
    private int depth;

    @Override
    public <T> T required(java.util.function.Supplier<T> work) {
      depth++;
      try {
        return work.get();
      } finally {
        depth--;
      }
    }

    boolean inTransaction() {
      return depth > 0;
    }
  }

  private static final class TrackingQuota implements MfaQuotaPort {
    private final TrackingTransactionRunner transactions;
    private int sourceCalls;
    private int failureCalls;

    private TrackingQuota(TrackingTransactionRunner transactions) {
      this.transactions = transactions;
    }

    @Override
    public void consume(MfaQuotaOperation operation, UUID userId, byte[] clientAddress) {
      assertThat(transactions.inTransaction()).isFalse();
    }

    @Override
    public void consumeRecoverySource(byte[] clientAddress) {
      assertThat(transactions.inTransaction()).isFalse();
      sourceCalls++;
    }

    @Override
    public void recordRecoveryFailure(UUID userId) {
      assertThat(transactions.inTransaction()).isFalse();
      failureCalls++;
    }
  }

  private static final class FixedCredentials implements SessionCredentialPort {
    @Override
    public String newSessionId() {
      return "s".repeat(43);
    }

    @Override
    public GeneratedRefreshCredential newRefreshCredential() {
      return new GeneratedRefreshCredential(
          "r".repeat(43), new RefreshDigest("refresh-k1", "refresh-hmac-v1", new byte[32]));
    }

    @Override
    public List<RefreshDigest> digestCandidates(String encodedCredential) {
      return List.of(new RefreshDigest("refresh-k1", "refresh-hmac-v1", new byte[32]));
    }
  }
}
