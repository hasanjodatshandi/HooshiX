package com.sajtech.identity.application.externalidentity.usecase;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.externalidentity.*;
import com.sajtech.identity.application.externalidentity.model.*;
import com.sajtech.identity.application.externalidentity.port.in.ExternalIdentityManagement;
import com.sajtech.identity.application.externalidentity.port.out.*;
import com.sajtech.identity.application.mfa.model.GeneratedMfaChallenge;
import com.sajtech.identity.application.mfa.port.out.MfaAuthenticationGate;
import com.sajtech.identity.application.mfa.port.out.MfaCryptographyPort;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Optional;
import java.util.UUID;

public final class ExternalIdentityUseCase implements ExternalIdentityManagement {
  private static final String GOOGLE_ISSUER = "https://accounts.google.com";
  private static final String ESTABLISH = "ESTABLISH_SESSION";
  private static final String LINK = "LINK";
  private static final Duration EVIDENCE_LIFETIME = Duration.ofMinutes(2);
  private static final Duration EVIDENCE_RETENTION = Duration.ofMinutes(10);
  private static final Duration RECENT_AUTH = Duration.ofMinutes(5);
  private static final Duration IDLE_LIFETIME = Duration.ofDays(7);
  private static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);
  private static final int ACTIVE_FAMILY_LIMIT = 20;

  private final ExternalIdentityStore external;
  private final ExternalIdentityFingerprintPort fingerprints;
  private final ExternalIdentityResultCryptoPort resultCrypto;
  private final AuthenticationStore authentication;
  private final RefreshCredentialLookup refreshLookup;
  private final SessionCredentialPort sessionCredentials;
  private final AuthenticationTenantSelectionPort tenantSelection;
  private final LoginQuotaPort quota;
  private final MfaAuthenticationGate mfa;
  private final MfaCryptographyPort mfaCryptography;
  private final ContactCanonicalizer contacts;
  private final TransactionRunner transactions;
  private final Clock clock;

  public ExternalIdentityUseCase(
      ExternalIdentityStore external,
      ExternalIdentityFingerprintPort fingerprints,
      ExternalIdentityResultCryptoPort resultCrypto,
      AuthenticationStore authentication,
      RefreshCredentialLookup refreshLookup,
      SessionCredentialPort sessionCredentials,
      AuthenticationTenantSelectionPort tenantSelection,
      LoginQuotaPort quota,
      MfaAuthenticationGate mfa,
      MfaCryptographyPort mfaCryptography,
      ContactCanonicalizer contacts,
      TransactionRunner transactions,
      Clock clock) {
    this.external = external;
    this.fingerprints = fingerprints;
    this.resultCrypto = resultCrypto;
    this.authentication = authentication;
    this.refreshLookup = refreshLookup;
    this.sessionCredentials = sessionCredentials;
    this.tenantSelection = tenantSelection;
    this.quota = quota;
    this.mfa = mfa;
    this.mfaCryptography = mfaCryptography;
    this.contacts = contacts;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public AuthenticationSession establish(
      UUID requestId, ExternalIdentityEvidence evidence, byte[] clientAddress) {
    validate(requestId, evidence, clientAddress);
    quota.checkSource(clientAddress.clone());
    Instant now = clock.instant();
    validateLifetime(evidence, now);
    byte[] material = fingerprintMaterial(ESTABLISH, requestId, evidence);
    FingerprintDigest fingerprint = fingerprints.digest(material);
    GeneratedRefreshCredential refresh = sessionCredentials.newRefreshCredential();
    GeneratedMfaChallenge challenge = mfaCryptography.generateChallenge();
    EstablishDecision decision =
        transactions.required(
            () -> {
              Optional<AuthenticationSession> replay =
                  replay(ESTABLISH, requestId, evidence, material);
              if (replay.isPresent()) return EstablishDecision.success(replay.get());
              external.lockSubject(evidence.issuer(), evidence.subject());
              ExternalIdentityStore.Binding binding =
                  external.findActiveBinding(evidence.issuer(), evidence.subject()).orElse(null);
              UUID userId;
              String userStatus;
              if (binding == null) {
                CanonicalContact email = canonicalVerifiedEmail(evidence);
                if (email != null) external.lockContactKey(email.canonicalValue());
                if (email != null
                    && external.verifiedEmailUnavailable(email.canonicalValue(), null)) {
                  external.saveEvidence(
                      evidence.evidenceId(),
                      requestId,
                      ESTABLISH,
                      evidence.issuer(),
                      evidence.subject(),
                      fingerprint,
                      "ACCOUNT_LINK_REQUIRED",
                      null,
                      null,
                      evidence.issuedAt(),
                      now,
                      now.plus(EVIDENCE_RETENTION));
                  return EstablishDecision.rejected(ExternalIdentityError.ACCOUNT_LINK_REQUIRED);
                }
                userId =
                    external.createExternalUser(
                        evidence.issuer(),
                        evidence.subject(),
                        email == null ? null : email.canonicalValue(),
                        email == null ? null : email.deliveryValue(),
                        boundedSuggestion(evidence.givenName()),
                        boundedSuggestion(evidence.familyName()),
                        now);
                userStatus = "PENDING";
              } else {
                userId = binding.userId();
                userStatus = binding.userStatus();
              }
              if (!"ACTIVE".equals(userStatus) && !"PENDING".equals(userStatus)) {
                throw error(ExternalIdentityError.INVALID_EVIDENCE);
              }
              AuthenticationSession result;
              String outcome;
              if (mfa.requiresMfa(userId)) {
                mfa.replaceLoginChallenge(
                    UUID.randomUUID(),
                    userId,
                    challenge,
                    PrimaryAuthenticationMethod.GOOGLE_OIDC,
                    now,
                    now.plus(Duration.ofMinutes(5)));
                result = AuthenticationSession.mfaRequired(userId, challenge.encoded());
                outcome = "MFA_REQUIRED";
              } else {
                AuthenticationTenantSelection selection =
                    "PENDING".equals(userStatus)
                        ? AuthenticationTenantSelection.onboarding()
                        : tenantSelection.resolveAfterPrimaryAuthentication(userId);
                PreparedSession prepared = prepared(userId, selection, refresh, now, null);
                enforceFamilyLimit(userId, now);
                authentication.createSession(prepared);
                result = session(prepared, refresh.encoded());
                outcome = "SESSION_ESTABLISHED";
              }
              saveResult(ESTABLISH, requestId, evidence, fingerprint, outcome, result, now);
              return EstablishDecision.success(result);
            });
    if (decision.error() != null) throw error(decision.error());
    return decision.session();
  }

  @Override
  public AuthenticationSession link(
      UUID requestId,
      String refreshCredential,
      ExternalIdentityEvidence evidence,
      byte[] clientAddress) {
    validate(requestId, evidence, clientAddress);
    if (refreshCredential == null) throw invalid();
    quota.checkSource(clientAddress.clone());
    Instant now = clock.instant();
    validateLifetime(evidence, now);
    LockedRefreshCredential observed = observedRecent(refreshCredential, now);
    byte[] material = fingerprintMaterial(LINK, requestId, evidence);
    FingerprintDigest fingerprint = fingerprints.digest(material);
    GeneratedRefreshCredential rotated = sessionCredentials.newRefreshCredential();
    return transactions.required(
        () -> {
          Optional<AuthenticationSession> replay = replay(LINK, requestId, evidence, material);
          if (replay.isPresent()) return replay.get();
          LockedRefreshCredential locked = lockedSame(refreshCredential, observed);
          requireRecent(locked, now);
          external.lockSubject(evidence.issuer(), evidence.subject());
          ExternalIdentityStore.Binding subject =
              external.findActiveBinding(evidence.issuer(), evidence.subject()).orElse(null);
          if (subject != null) {
            if (!subject.userId().equals(locked.userId())) {
              throw error(ExternalIdentityError.ACCOUNT_LINK_REQUIRED);
            }
            throw error(ExternalIdentityError.IDENTITY_ALREADY_LINKED);
          }
          if (external.findActiveBinding(locked.userId(), evidence.issuer()).isPresent()) {
            throw error(ExternalIdentityError.IDENTITY_ALREADY_LINKED);
          }
          external.link(
              UUID.randomUUID(), locked.userId(), evidence.issuer(), evidence.subject(), now);
          AuthenticationSession result = rotateSecurityState(locked, rotated, now);
          saveResult(LINK, requestId, evidence, fingerprint, "LINKED", result, now);
          return result;
        });
  }

  @Override
  public AuthenticationSession unlink(UUID requestId, String refreshCredential, String issuer) {
    if (requestId == null || refreshCredential == null || !GOOGLE_ISSUER.equals(issuer)) {
      throw invalid();
    }
    Instant now = clock.instant();
    LockedRefreshCredential observed = observedRecent(refreshCredential, now);
    GeneratedRefreshCredential rotated = sessionCredentials.newRefreshCredential();
    return transactions.required(
        () -> {
          LockedRefreshCredential locked = lockedSame(refreshCredential, observed);
          requireRecent(locked, now);
          ExternalIdentityStore.Binding binding =
              external
                  .findActiveBinding(locked.userId(), issuer)
                  .orElseThrow(() -> error(ExternalIdentityError.IDENTITY_NOT_LINKED));
          int externalCount = external.activeExternalIdentityCount(locked.userId());
          if (externalCount < 1) throw error(ExternalIdentityError.SESSION_STATE_INVALID);
          if (externalCount == 1 && !external.hasLocalCredential(locked.userId())) {
            throw error(ExternalIdentityError.LAST_AUTHENTICATION_METHOD);
          }
          external.unlink(binding.externalIdentityId(), now);
          return rotateSecurityState(locked, rotated, now);
        });
  }

  @Override
  public boolean googleLinked(UUID requestId, String refreshCredential) {
    if (requestId == null || refreshCredential == null) throw invalid();
    Instant now = clock.instant();
    LockedRefreshCredential current =
        refreshLookup.find(authentication, refreshCredential).orElseThrow(() -> invalidSession());
    requireUsable(current, now);
    return external.findActiveBinding(current.userId(), GOOGLE_ISSUER).isPresent();
  }

  private Optional<AuthenticationSession> replay(
      String operation, UUID requestId, ExternalIdentityEvidence evidence, byte[] material) {
    external.lockRequest(requestId, operation);
    external.lockEvidence(evidence.evidenceId());
    Optional<ExternalIdentityStore.ConsumedEvidence> prior =
        external.findEvidence(evidence.evidenceId());
    if (prior.isEmpty()) {
      if (external.findEvidenceByRequest(requestId, operation).isPresent()) {
        throw error(ExternalIdentityError.EVIDENCE_REPLAY);
      }
      return Optional.empty();
    }
    ExternalIdentityStore.ConsumedEvidence stored = prior.get();
    if (!stored.requestId().equals(requestId)
        || !stored.operation().equals(operation)
        || !stored.issuer().equals(evidence.issuer())
        || !stored.subject().equals(evidence.subject())
        || !fingerprints.matches(
            material,
            stored.fingerprint(),
            stored.fingerprintKeyId(),
            stored.fingerprintVersion())) {
      throw error(ExternalIdentityError.EVIDENCE_REPLAY);
    }
    if ("ACCOUNT_LINK_REQUIRED".equals(stored.outcome())) {
      throw error(ExternalIdentityError.ACCOUNT_LINK_REQUIRED);
    }
    if (stored.encryptedResult() == null) {
      throw error(ExternalIdentityError.SESSION_STATE_INVALID);
    }
    byte[] clear = resultCrypto.decrypt(evidence.evidenceId(), operation, stored.encryptedResult());
    return Optional.of(decodeSession(clear));
  }

  private void saveResult(
      String operation,
      UUID requestId,
      ExternalIdentityEvidence evidence,
      FingerprintDigest fingerprint,
      String outcome,
      AuthenticationSession result,
      Instant now) {
    external.saveEvidence(
        evidence.evidenceId(),
        requestId,
        operation,
        evidence.issuer(),
        evidence.subject(),
        fingerprint,
        outcome,
        result.userId(),
        resultCrypto.encrypt(evidence.evidenceId(), operation, encodeSession(result)),
        evidence.issuedAt(),
        now,
        now.plus(EVIDENCE_RETENTION));
  }

  private PreparedSession prepared(
      UUID userId,
      AuthenticationTenantSelection selection,
      GeneratedRefreshCredential refresh,
      Instant now,
      Instant mfaAt) {
    return new PreparedSession(
        UUID.randomUUID(),
        sessionCredentials.newSessionId(),
        userId,
        UUID.randomUUID(),
        refresh.digest(),
        now,
        now,
        now.plus(IDLE_LIFETIME),
        now.plus(ABSOLUTE_LIFETIME),
        selection.mode(),
        selection.tenantId(),
        selection.membershipId(),
        mfaAt,
        PrimaryAuthenticationMethod.GOOGLE_OIDC);
  }

  private AuthenticationSession rotateSecurityState(
      LockedRefreshCredential locked, GeneratedRefreshCredential rotated, Instant now) {
    Instant idle = minimum(now.plus(IDLE_LIFETIME), locked.absoluteExpiresAt());
    if (!now.isBefore(idle)) throw invalidSession();
    authentication.rotateRefresh(locked, UUID.randomUUID(), rotated.digest(), now, idle);
    authentication.revokeOtherFamilies(
        locked.userId(),
        locked.refreshFamilyId(),
        RefreshFamilyRevocationReason.EXTERNAL_IDENTITY_CHANGED,
        now);
    return new AuthenticationSession(
        locked.sessionId(),
        locked.refreshFamilyId(),
        locked.userId(),
        rotated.encoded(),
        idle,
        locked.absoluteExpiresAt(),
        locked.sessionMode(),
        locked.selectedTenantId(),
        locked.selectedMembershipId());
  }

  private void enforceFamilyLimit(UUID userId, Instant now) {
    authentication.expireDueFamilies(userId, now);
    int active = authentication.countActiveFamilies(userId);
    if (active < 0 || active > ACTIVE_FAMILY_LIMIT) {
      throw error(ExternalIdentityError.SESSION_STATE_INVALID);
    }
    if (active == ACTIVE_FAMILY_LIMIT) {
      UUID oldest =
          authentication
              .oldestActiveFamily(userId)
              .orElseThrow(() -> error(ExternalIdentityError.SESSION_STATE_INVALID));
      authentication.revokeFamily(oldest, RefreshFamilyRevocationReason.ACTIVE_FAMILY_LIMIT, now);
    }
  }

  private LockedRefreshCredential observedRecent(String credential, Instant now) {
    LockedRefreshCredential observed =
        refreshLookup.find(authentication, credential).orElseThrow(() -> invalidSession());
    requireRecent(observed, now);
    return observed;
  }

  private LockedRefreshCredential lockedSame(String credential, LockedRefreshCredential observed) {
    LockedRefreshCredential locked =
        refreshLookup.lock(authentication, credential).orElseThrow(() -> invalidSession());
    if (!locked.refreshFamilyId().equals(observed.refreshFamilyId())
        || !locked.credentialId().equals(observed.credentialId())) throw invalidSession();
    return locked;
  }

  private void requireRecent(LockedRefreshCredential current, Instant now) {
    requireUsable(current, now);
    if (current.authenticatedAt() == null
        || current.authenticatedAt().plus(RECENT_AUTH).isBefore(now)
        || (mfa.requiresMfa(current.userId())
            && (current.mfaAuthenticatedAt() == null
                || current.mfaAuthenticatedAt().plus(RECENT_AUTH).isBefore(now)))) {
      throw error(ExternalIdentityError.RECENT_AUTH_REQUIRED);
    }
  }

  private static void requireUsable(LockedRefreshCredential current, Instant now) {
    if (!"ACTIVE".equals(current.credentialState())
        || !"ACTIVE".equals(current.familyState())
        || !"ACTIVE".equals(current.userStatus())
        || !now.isBefore(current.idleExpiresAt())
        || !now.isBefore(current.absoluteExpiresAt())) throw invalidSession();
  }

  private CanonicalContact canonicalVerifiedEmail(ExternalIdentityEvidence evidence) {
    if (!evidence.emailVerified() || evidence.email() == null || evidence.email().isBlank()) {
      return null;
    }
    try {
      return contacts.canonicalize(RegistrationChannel.EMAIL, evidence.email());
    } catch (RegistrationException exception) {
      throw error(ExternalIdentityError.INVALID_EVIDENCE);
    }
  }

  private static String boundedSuggestion(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized =
        java.text.Normalizer.normalize(value.strip(), java.text.Normalizer.Form.NFC);
    if (normalized.codePointCount(0, normalized.length()) > 120
        || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw error(ExternalIdentityError.INVALID_EVIDENCE);
    }
    return normalized;
  }

  private static void validate(
      UUID requestId, ExternalIdentityEvidence evidence, byte[] clientAddress) {
    if (requestId == null
        || evidence == null
        || evidence.evidenceId() == null
        || evidence.evidenceId().length != 32
        || evidence.issuedAt() == null
        || !GOOGLE_ISSUER.equals(evidence.issuer())
        || evidence.subject() == null
        || !evidence.subject().matches("[A-Za-z0-9_-]{1,255}")
        || evidence.metadataVersion() != 1
        || clientAddress == null
        || (clientAddress.length != 4 && clientAddress.length != 16)
        || (evidence.emailVerified() && (evidence.email() == null || evidence.email().isBlank()))) {
      throw invalid();
    }
  }

  private static void validateLifetime(ExternalIdentityEvidence evidence, Instant now) {
    if (evidence.issuedAt().isAfter(now.plusSeconds(30))
        || !now.isBefore(evidence.issuedAt().plus(EVIDENCE_LIFETIME))) {
      throw error(ExternalIdentityError.EVIDENCE_EXPIRED);
    }
  }

  private static byte[] fingerprintMaterial(
      String operation, UUID requestId, ExternalIdentityEvidence evidence) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      write(out, operation);
      write(out, requestId.toString());
      out.writeInt(evidence.evidenceId().length);
      out.write(evidence.evidenceId());
      out.writeLong(evidence.issuedAt().getEpochSecond());
      out.writeInt(evidence.issuedAt().getNano());
      write(out, evidence.issuer());
      write(out, evidence.subject());
      out.writeInt(evidence.metadataVersion());
      writeNullable(out, evidence.email());
      out.writeBoolean(evidence.emailVerified());
      writeNullable(out, evidence.givenName());
      writeNullable(out, evidence.familyName());
      out.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("OIDC fingerprint encoding failed", impossible);
    }
  }

  private static byte[] encodeSession(AuthenticationSession session) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(1);
      writeNullable(out, session.sessionId());
      writeNullable(out, uuid(session.refreshFamilyId()));
      write(out, session.userId().toString());
      writeNullable(out, session.refreshCredential());
      instant(out, session.idleExpiresAt());
      instant(out, session.absoluteExpiresAt());
      write(out, session.mode().name());
      writeNullable(out, uuid(session.selectedTenantId()));
      writeNullable(out, uuid(session.selectedMembershipId()));
      writeNullable(out, session.mfaChallenge());
      out.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("OIDC result encoding failed", impossible);
    }
  }

  private static AuthenticationSession decodeSession(byte[] encoded) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
      if (in.readInt() != 1) throw new IOException("version");
      AuthenticationSession result =
          new AuthenticationSession(
              readNullable(in),
              parseUuid(readNullable(in)),
              UUID.fromString(read(in)),
              readNullable(in),
              readInstant(in),
              readInstant(in),
              AuthenticationSessionMode.valueOf(read(in)),
              parseUuid(readNullable(in)),
              parseUuid(readNullable(in)),
              readNullable(in));
      if (in.read() != -1) throw new IOException("trailing");
      return result;
    } catch (IOException | IllegalArgumentException exception) {
      throw error(ExternalIdentityError.SESSION_STATE_INVALID);
    }
  }

  private static AuthenticationSession session(PreparedSession prepared, String refreshCredential) {
    return new AuthenticationSession(
        prepared.sessionId(),
        prepared.refreshFamilyId(),
        prepared.userId(),
        refreshCredential,
        prepared.idleExpiresAt(),
        prepared.absoluteExpiresAt(),
        prepared.mode(),
        prepared.selectedTenantId(),
        prepared.selectedMembershipId());
  }

  private static void write(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String read(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > 4096) throw new IOException("size");
    byte[] bytes = in.readNBytes(size);
    if (bytes.length != size) throw new IOException("truncated");
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void writeNullable(DataOutputStream out, String value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) write(out, value);
  }

  private static String readNullable(DataInputStream in) throws IOException {
    return in.readBoolean() ? read(in) : null;
  }

  private static void instant(DataOutputStream out, Instant value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) {
      out.writeLong(value.getEpochSecond());
      out.writeInt(value.getNano());
    }
  }

  private static Instant readInstant(DataInputStream in) throws IOException {
    return in.readBoolean() ? Instant.ofEpochSecond(in.readLong(), in.readInt()) : null;
  }

  private static String uuid(UUID value) {
    return value == null ? null : value.toString();
  }

  private static UUID parseUuid(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  private static Instant minimum(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static ExternalIdentityException invalid() {
    return error(ExternalIdentityError.INVALID_ARGUMENT);
  }

  private static ExternalIdentityException invalidSession() {
    return error(ExternalIdentityError.INVALID_SESSION);
  }

  private static ExternalIdentityException error(ExternalIdentityError error) {
    return new ExternalIdentityException(error, "External identity operation failed");
  }

  private record EstablishDecision(AuthenticationSession session, ExternalIdentityError error) {
    static EstablishDecision success(AuthenticationSession session) {
      return new EstablishDecision(session, null);
    }

    static EstablishDecision rejected(ExternalIdentityError error) {
      return new EstablishDecision(null, error);
    }
  }
}
