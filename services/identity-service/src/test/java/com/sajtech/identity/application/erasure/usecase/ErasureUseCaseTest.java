package com.sajtech.identity.application.erasure.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.authentication.model.AuthenticationSessionMode;
import com.sajtech.identity.application.authentication.model.LockedRefreshCredential;
import com.sajtech.identity.application.authentication.model.RefreshDigest;
import com.sajtech.identity.application.authentication.model.RefreshFamilyRevocationReason;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.application.erasure.ErasureError;
import com.sajtech.identity.application.erasure.ErasureException;
import com.sajtech.identity.application.erasure.model.ErasureRequestView;
import com.sajtech.identity.application.erasure.port.in.RequestSelfErasureCommand;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
import com.sajtech.identity.application.mfa.model.EncryptedTotpSecret;
import com.sajtech.identity.application.mfa.model.MfaProof;
import com.sajtech.identity.application.mfa.model.MfaProofType;
import com.sajtech.identity.application.mfa.port.out.MfaCryptographyPort;
import com.sajtech.identity.application.mfa.port.out.MfaStore;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ErasureUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void acceptanceIsOneLocalTransactionAndRevokesEveryRefreshFamily() {
    Fixture fixture = new Fixture();
    UUID requestId = UUID.randomUUID();
    ErasureRequestView accepted =
        new ErasureRequestView(requestId, fixture.userId, "REQUESTED", "1", NOW, null);
    when(fixture.erasure.find(requestId)).thenReturn(Optional.empty(), Optional.empty());
    when(fixture.erasure.accept(requestId, fixture.userId, NOW)).thenReturn(accepted);

    ErasureRequestView result =
        fixture.useCase.requestSelfErasure(
            new RequestSelfErasureCommand(requestId, "refresh", null, "ERASE_MY_ACCOUNT"));

    assertThat(result).isEqualTo(accepted);
    assertThat(fixture.transactions.calls).isEqualTo(1);
    verify(fixture.erasure).accept(requestId, fixture.userId, NOW);
    verify(fixture.authentication)
        .revokeAllFamilies(fixture.userId, RefreshFamilyRevocationReason.ERASURE_REQUESTED, NOW);
  }

  @Test
  void stalePrimaryAuthenticationIsRejectedBeforeTheTransaction() {
    Fixture fixture = new Fixture(NOW.minusSeconds(301));

    assertThatThrownBy(
            () ->
                fixture.useCase.requestSelfErasure(
                    new RequestSelfErasureCommand(
                        UUID.randomUUID(), "refresh", null, "ERASE_MY_ACCOUNT")))
        .isInstanceOfSatisfying(
            ErasureException.class,
            error ->
                assertThat(error.error()).isEqualTo(ErasureError.RECENT_AUTHENTICATION_REQUIRED));
    assertThat(fixture.transactions.calls).isZero();
    verify(fixture.erasure, never()).accept(any(), any(), any());
  }

  @Test
  void activeMfaRequiresAValidNonReplayableProof() {
    Fixture fixture = new Fixture();
    UUID enrollmentId = UUID.randomUUID();
    var active =
        new MfaStore.ActiveEnrollment(
            enrollmentId,
            fixture.userId,
            new EncryptedTotpSecret("key", new byte[12], new byte[48]),
            10L);
    when(fixture.mfa.requiresMfa(fixture.userId)).thenReturn(true);
    when(fixture.mfa.lockActiveEnrollment(fixture.userId)).thenReturn(Optional.of(active));
    when(fixture.mfaCryptography.verifyTotp(
            eq(fixture.userId), eq(enrollmentId), eq(active.secret()), eq("123456"), eq(NOW)))
        .thenReturn(OptionalLong.of(11));
    UUID requestId = UUID.randomUUID();
    when(fixture.erasure.accept(requestId, fixture.userId, NOW))
        .thenReturn(new ErasureRequestView(requestId, fixture.userId, "REQUESTED", "1", NOW, null));

    fixture.useCase.requestSelfErasure(
        new RequestSelfErasureCommand(
            requestId, "refresh", new MfaProof(MfaProofType.TOTP, "123456"), "ERASE_MY_ACCOUNT"));

    verify(fixture.mfa).acceptTotp(enrollmentId, 11, NOW);
  }

  @Test
  void acceptedRequestReplaysEvenAfterItsSessionWasRevoked() {
    Fixture fixture = new Fixture();
    UUID requestId = UUID.randomUUID();
    ErasureRequestView accepted =
        new ErasureRequestView(requestId, fixture.userId, "IN_PROGRESS", "1", NOW, null);
    when(fixture.erasure.find(requestId)).thenReturn(Optional.of(accepted));

    assertThat(
            fixture.useCase.requestSelfErasure(
                new RequestSelfErasureCommand(requestId, "refresh", null, "ERASE_MY_ACCOUNT")))
        .isEqualTo(accepted);
    assertThat(fixture.transactions.calls).isZero();
    verify(fixture.authentication, never()).revokeAllFamilies(any(), any(), any());
  }

  private static final class Fixture {
    final UUID userId = UUID.randomUUID();
    final ErasureStore erasure = mock(ErasureStore.class);
    final AuthenticationStore authentication = mock(AuthenticationStore.class);
    final SessionCredentialPort credentials = mock(SessionCredentialPort.class);
    final MfaStore mfa = mock(MfaStore.class);
    final MfaCryptographyPort mfaCryptography = mock(MfaCryptographyPort.class);
    final TrackingTransactions transactions = new TrackingTransactions();
    final ErasureUseCase useCase;

    Fixture() {
      this(NOW.minusSeconds(30));
    }

    Fixture(Instant authenticatedAt) {
      RefreshDigest digest = new RefreshDigest("key", "version", new byte[32]);
      LockedRefreshCredential session = session(userId, authenticatedAt);
      when(credentials.digestCandidates("refresh")).thenReturn(List.of(digest));
      when(authentication.findRefreshCredential(digest)).thenReturn(Optional.of(session));
      when(authentication.lockRefreshCredential(digest)).thenReturn(Optional.of(session));
      useCase =
          new ErasureUseCase(
              erasure, authentication, credentials, mfa, mfaCryptography, transactions, CLOCK);
    }
  }

  private static LockedRefreshCredential session(UUID userId, Instant authenticatedAt) {
    return new LockedRefreshCredential(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "S".repeat(43),
        userId,
        "ACTIVE",
        "ACTIVE",
        "ACTIVE",
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
        null,
        null,
        authenticatedAt,
        NOW.plusSeconds(600),
        NOW.plusSeconds(3600),
        null);
  }

  private static final class TrackingTransactions implements TransactionRunner {
    int calls;

    @Override
    public <T> T required(Supplier<T> work) {
      calls++;
      return work.get();
    }
  }
}
