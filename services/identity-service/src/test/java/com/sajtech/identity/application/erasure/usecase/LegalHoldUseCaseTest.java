package com.sajtech.identity.application.erasure.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.authentication.model.AuthenticationSessionMode;
import com.sajtech.identity.application.authentication.model.LockedRefreshCredential;
import com.sajtech.identity.application.authentication.model.RefreshDigest;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.application.erasure.model.LegalHoldView;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
import com.sajtech.identity.application.mfa.model.EncryptedTotpSecret;
import com.sajtech.identity.application.mfa.model.MfaProof;
import com.sajtech.identity.application.mfa.model.MfaProofType;
import com.sajtech.identity.application.mfa.port.out.MfaCryptographyPort;
import com.sajtech.identity.application.mfa.port.out.MfaStore;
import com.sajtech.identity.application.tenant.port.out.AuthorizationTenantPort;
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

class LegalHoldUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void consumesMfaInsideTheLocalTransactionAfterRemoteAuthorization() {
    UUID userId = UUID.randomUUID();
    UUID enrollmentId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID erasureRequestId = UUID.randomUUID();
    UUID holdId = UUID.randomUUID();
    AuthenticationStore authentication = mock(AuthenticationStore.class);
    SessionCredentialPort credentials = mock(SessionCredentialPort.class);
    MfaStore mfa = mock(MfaStore.class);
    MfaCryptographyPort cryptography = mock(MfaCryptographyPort.class);
    AuthorizationTenantPort authorization = mock(AuthorizationTenantPort.class);
    ErasureStore store = mock(ErasureStore.class);
    TrackingTransactions transactions = new TrackingTransactions();
    RefreshDigest digest = new RefreshDigest("key", "version", new byte[32]);
    LockedRefreshCredential session = session(userId);
    var active =
        new MfaStore.ActiveEnrollment(
            enrollmentId, userId, new EncryptedTotpSecret("key", new byte[12], new byte[48]), 10L);
    LegalHoldView expected = new LegalHoldView(holdId, erasureRequestId, "ACTIVE", "1", NOW, null);

    when(credentials.digestCandidates("refresh")).thenReturn(List.of(digest));
    when(authentication.findRefreshCredential(digest)).thenReturn(Optional.of(session));
    when(authentication.lockRefreshCredential(digest)).thenReturn(Optional.of(session));
    when(mfa.requiresMfa(userId)).thenReturn(true);
    when(mfa.lockActiveEnrollment(userId))
        .thenAnswer(
            ignored -> {
              assertThat(transactions.inside).isTrue();
              return Optional.of(active);
            });
    when(cryptography.verifyTotp(userId, enrollmentId, active.secret(), "123456", NOW))
        .thenReturn(OptionalLong.of(11));
    doAnswer(
            ignored -> {
              assertThat(transactions.inside).isFalse();
              return null;
            })
        .when(authorization)
        .checkPlatformPermission(userId, "platform.legal_hold.manage");
    when(store.createHold(requestId, erasureRequestId, "LEGAL-CASE-1", userId, NOW))
        .thenAnswer(
            ignored -> {
              assertThat(transactions.inside).isTrue();
              return expected;
            });

    LegalHoldUseCase useCase =
        new LegalHoldUseCase(
            store,
            authentication,
            credentials,
            mfa,
            cryptography,
            authorization,
            transactions,
            CLOCK);

    LegalHoldView result =
        useCase.create(
            requestId,
            "refresh",
            erasureRequestId,
            "LEGAL-CASE-1",
            new MfaProof(MfaProofType.TOTP, "123456"));

    assertThat(result).isEqualTo(expected);
    assertThat(transactions.calls).isOne();
    verify(mfa).acceptTotp(enrollmentId, 11, NOW);
    verify(authorization).checkPlatformPermission(userId, "platform.legal_hold.manage");
  }

  private static LockedRefreshCredential session(UUID userId) {
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
        NOW.minusSeconds(30),
        NOW.plusSeconds(600),
        NOW.plusSeconds(3600),
        null);
  }

  private static final class TrackingTransactions implements TransactionRunner {
    int calls;
    boolean inside;

    @Override
    public <T> T required(Supplier<T> work) {
      calls++;
      inside = true;
      try {
        return work.get();
      } finally {
        inside = false;
      }
    }
  }
}
