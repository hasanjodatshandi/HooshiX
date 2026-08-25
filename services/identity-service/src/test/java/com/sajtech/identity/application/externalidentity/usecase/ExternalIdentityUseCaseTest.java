package com.sajtech.identity.application.externalidentity.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.externalidentity.*;
import com.sajtech.identity.application.externalidentity.model.*;
import com.sajtech.identity.application.externalidentity.port.out.*;
import com.sajtech.identity.application.mfa.model.*;
import com.sajtech.identity.application.mfa.port.out.*;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;

class ExternalIdentityUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");
  private static final UUID REQUEST = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
  private static final UUID USER = UUID.fromString("8b4a781d-03d9-4b3c-a758-6f447742940c");
  private final ExternalIdentityStore external = mock(ExternalIdentityStore.class);
  private final ExternalIdentityFingerprintPort fingerprints =
      mock(ExternalIdentityFingerprintPort.class);
  private final ExternalIdentityResultCryptoPort resultCrypto =
      mock(ExternalIdentityResultCryptoPort.class);
  private final AuthenticationStore authentication = mock(AuthenticationStore.class);
  private final SessionCredentialPort credentials = mock(SessionCredentialPort.class);
  private final AuthenticationTenantSelectionPort tenants =
      mock(AuthenticationTenantSelectionPort.class);
  private final LoginQuotaPort quota = mock(LoginQuotaPort.class);
  private final MfaAuthenticationGate mfa = mock(MfaAuthenticationGate.class);
  private final MfaCryptographyPort mfaCrypto = mock(MfaCryptographyPort.class);
  private ExternalIdentityUseCase useCase;

  @BeforeEach
  void setUp() {
    RefreshDigest refreshDigest = new RefreshDigest("r1", "v1", new byte[32]);
    when(credentials.newRefreshCredential())
        .thenReturn(new GeneratedRefreshCredential("r".repeat(43), refreshDigest));
    when(credentials.newSessionId()).thenReturn("s".repeat(43));
    when(mfaCrypto.generateChallenge())
        .thenReturn(
            new GeneratedMfaChallenge("m".repeat(43), new MfaDigest(new byte[32], "m1", "v1")));
    when(fingerprints.digest(any()))
        .thenReturn(new FingerprintDigest(new byte[32], "oidc-evidence-hmac-v1", "f1"));
    when(resultCrypto.encrypt(any(), anyString(), any()))
        .thenReturn(new EncryptedExternalIdentityResult("e1", new byte[12], new byte[32]));
    when(authentication.countActiveFamilies(any())).thenReturn(0);
    when(tenants.resolveAfterPrimaryAuthentication(any()))
        .thenReturn(AuthenticationTenantSelection.onboarding());
    useCase =
        new ExternalIdentityUseCase(
            external,
            fingerprints,
            resultCrypto,
            authentication,
            new RefreshCredentialLookup(credentials),
            credentials,
            tenants,
            quota,
            mfa,
            mfaCrypto,
            new ContactCanonicalizer(),
            new DirectTransactions(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void unknownIdentityCreatesPendingOnboardingWithoutUsingUnverifiedEmail() {
    when(external.findEvidence(any())).thenReturn(Optional.empty());
    when(external.findActiveBinding("https://accounts.google.com", "google-subject"))
        .thenReturn(Optional.empty());
    when(external.createExternalUser(
            eq("https://accounts.google.com"),
            eq("google-subject"),
            isNull(),
            isNull(),
            eq("Google"),
            eq("Person"),
            eq(NOW)))
        .thenReturn(USER);

    AuthenticationSession result = useCase.establish(REQUEST, evidence(false), address());

    assertThat(result.userId()).isEqualTo(USER);
    assertThat(result.mode()).isEqualTo(AuthenticationSessionMode.AUTHENTICATED_ONBOARDING);
    assertThat(result.refreshCredential()).isEqualTo("r".repeat(43));
    verify(authentication)
        .createSession(
            argThat(
                session ->
                    session.userId().equals(USER)
                        && session.authenticationMethod()
                            == PrimaryAuthenticationMethod.GOOGLE_OIDC));
    verify(external)
        .saveEvidence(
            any(),
            eq(REQUEST),
            eq("ESTABLISH_SESSION"),
            anyString(),
            eq("google-subject"),
            any(),
            eq("SESSION_ESTABLISHED"),
            eq(USER),
            any(),
            eq(NOW),
            eq(NOW),
            eq(NOW.plus(Duration.ofMinutes(10))));
  }

  @Test
  void verifiedEmailCollisionRequiresExplicitAccountLinkAndCreatesNoUser() {
    when(external.findEvidence(any())).thenReturn(Optional.empty());
    when(external.findActiveBinding(anyString(), anyString())).thenReturn(Optional.empty());
    when(external.verifiedEmailUnavailable("person@example.com", null)).thenReturn(true);

    assertThatThrownBy(() -> useCase.establish(REQUEST, evidence(true), address()))
        .isInstanceOfSatisfying(
            ExternalIdentityException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(ExternalIdentityError.ACCOUNT_LINK_REQUIRED));

    verify(external, never()).createExternalUser(any(), any(), any(), any(), any(), any(), any());
    verify(authentication, never()).createSession(any());
    verify(external).lockContactKey("person@example.com");
    verify(external)
        .saveEvidence(
            any(),
            eq(REQUEST),
            eq("ESTABLISH_SESSION"),
            anyString(),
            anyString(),
            any(),
            eq("ACCOUNT_LINK_REQUIRED"),
            isNull(),
            isNull(),
            eq(NOW),
            eq(NOW),
            any());
  }

  @Test
  void existingGoogleIdentityWithTotpProducesOnlyGoogleBoundMfaContinuation() {
    when(external.findEvidence(any())).thenReturn(Optional.empty());
    when(external.findActiveBinding(anyString(), anyString()))
        .thenReturn(
            Optional.of(new ExternalIdentityStore.Binding(UUID.randomUUID(), USER, "ACTIVE")));
    when(mfa.requiresMfa(USER)).thenReturn(true);

    AuthenticationSession result = useCase.establish(REQUEST, evidence(true), address());

    assertThat(result.mode()).isEqualTo(AuthenticationSessionMode.MFA_REQUIRED);
    assertThat(result.refreshCredential()).isNull();
    verify(authentication, never()).createSession(any());
    verify(mfa)
        .replaceLoginChallenge(
            any(),
            eq(USER),
            any(),
            eq(PrimaryAuthenticationMethod.GOOGLE_OIDC),
            eq(NOW),
            eq(NOW.plus(Duration.ofMinutes(5))));
  }

  @Test
  void evidenceReuseWithDifferentFingerprintFailsClosedBeforeSubjectMutation() {
    when(external.findEvidence(any()))
        .thenReturn(
            Optional.of(
                new ExternalIdentityStore.ConsumedEvidence(
                    new byte[32],
                    REQUEST,
                    "ESTABLISH_SESSION",
                    "https://accounts.google.com",
                    "google-subject",
                    new byte[32],
                    "f1",
                    "oidc-evidence-hmac-v1",
                    "SESSION_ESTABLISHED",
                    USER,
                    new EncryptedExternalIdentityResult("e1", new byte[12], new byte[32]))));
    when(fingerprints.matches(any(), any(), anyString(), anyString())).thenReturn(false);

    assertThatThrownBy(() -> useCase.establish(REQUEST, evidence(true), address()))
        .isInstanceOfSatisfying(
            ExternalIdentityException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(ExternalIdentityError.EVIDENCE_REPLAY));

    verify(external, never()).lockSubject(anyString(), anyString());
    verify(authentication, never()).createSession(any());
  }

  @Test
  void unlinkRejectsRemovingLastAuthenticationMethod() {
    LockedRefreshCredential current = activeRefresh();
    when(credentials.digestCandidates("r".repeat(43)))
        .thenReturn(List.of(new RefreshDigest("r1", "v1", new byte[32])));
    when(authentication.findRefreshCredential(any())).thenReturn(Optional.of(current));
    when(authentication.lockRefreshCredential(any())).thenReturn(Optional.of(current));
    UUID externalId = UUID.randomUUID();
    when(external.findActiveBinding(USER, "https://accounts.google.com"))
        .thenReturn(Optional.of(new ExternalIdentityStore.Binding(externalId, USER, "ACTIVE")));
    when(external.activeExternalIdentityCount(USER)).thenReturn(1);
    when(external.hasLocalCredential(USER)).thenReturn(false);

    assertThatThrownBy(() -> useCase.unlink(REQUEST, "r".repeat(43), "https://accounts.google.com"))
        .isInstanceOfSatisfying(
            ExternalIdentityException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(ExternalIdentityError.LAST_AUTHENTICATION_METHOD));

    verify(external, never()).unlink(any(), any());
  }

  private static ExternalIdentityEvidence evidence(boolean verified) {
    return new ExternalIdentityEvidence(
        new byte[32],
        NOW,
        "https://accounts.google.com",
        "google-subject",
        1,
        "Person@Example.com",
        verified,
        "Google",
        "Person");
  }

  private static LockedRefreshCredential activeRefresh() {
    return new LockedRefreshCredential(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "s".repeat(43),
        USER,
        "ACTIVE",
        "ACTIVE",
        "ACTIVE",
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
        null,
        null,
        NOW.minusSeconds(60),
        NOW.plus(Duration.ofDays(7)),
        NOW.plus(Duration.ofDays(30)),
        null);
  }

  private static byte[] address() {
    return new byte[] {127, 0, 0, 1};
  }

  private static final class DirectTransactions implements TransactionRunner {
    @Override
    public <T> T required(Supplier<T> work) {
      return work.get();
    }
  }
}
