package com.sajtech.identity.application.profile.usecase;

import com.sajtech.identity.application.authentication.model.LockedRefreshCredential;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.profile.*;
import com.sajtech.identity.application.profile.model.*;
import com.sajtech.identity.application.profile.port.in.ProfileManagement;
import com.sajtech.identity.application.profile.port.out.ProfileContactStore;
import com.sajtech.identity.application.profile.service.ProfileFingerprintEncoder;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.registration.service.ProfileCanonicalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.*;
import java.time.*;
import java.util.*;

public final class ProfileManagementUseCase implements ProfileManagement {
  private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
  private static final Duration RESEND_SPACING = Duration.ofSeconds(60);
  private static final Duration RECENT_AUTH_AGE = Duration.ofMinutes(5);
  private static final int MAX_ACTIVE_CONTACTS = 10;
  private static final int CONTACT_QUERY_LIMIT = MAX_ACTIVE_CONTACTS + 1;
  private final ProfileContactStore store;
  private final AuthenticationStore authenticationStore;
  private final RefreshCredentialLookup lookup;
  private final ContactCanonicalizer contactCanonicalizer;
  private final ProfileCanonicalizer profileCanonicalizer;
  private final ProfileFingerprintEncoder encoder;
  private final IntentFingerprintPort fingerprints;
  private final ChallengeSecretPort challenges;
  private final NotificationEscrowPort escrow;
  private final TransactionRunner transactions;
  private final Clock clock;

  public ProfileManagementUseCase(
      ProfileContactStore store,
      AuthenticationStore authenticationStore,
      RefreshCredentialLookup lookup,
      ContactCanonicalizer contactCanonicalizer,
      ProfileCanonicalizer profileCanonicalizer,
      ProfileFingerprintEncoder encoder,
      IntentFingerprintPort fingerprints,
      ChallengeSecretPort challenges,
      NotificationEscrowPort escrow,
      TransactionRunner transactions,
      Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.authenticationStore = Objects.requireNonNull(authenticationStore);
    this.lookup = Objects.requireNonNull(lookup);
    this.contactCanonicalizer = Objects.requireNonNull(contactCanonicalizer);
    this.profileCanonicalizer = Objects.requireNonNull(profileCanonicalizer);
    this.encoder = Objects.requireNonNull(encoder);
    this.fingerprints = Objects.requireNonNull(fingerprints);
    this.challenges = Objects.requireNonNull(challenges);
    this.escrow = Objects.requireNonNull(escrow);
    this.transactions = Objects.requireNonNull(transactions);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public ProfileContactStore.ProfileRecord profile(String refreshCredential) {
    return transactions.required(
        () -> {
          UUID userId = requireUsable(lock(refreshCredential), clock.instant(), false).userId();
          ProfileContactStore.ProfileRecord result = store.findProfile(userId);
          if (result == null) throw error(ProfileError.NOT_FOUND);
          return result;
        });
  }

  @Override
  public void update(
      String refreshCredential,
      UUID requestId,
      String firstName,
      String lastName,
      String fatherName) {
    requireRequestId(requestId);
    RegistrationProfile profile;
    try {
      profile = profileCanonicalizer.canonicalize(firstName, lastName, fatherName);
    } catch (RegistrationException exception) {
      throw error(ProfileError.INVALID_ARGUMENT);
    }
    byte[] material = encoder.update(profile);
    try {
      transactions.required(
          () -> {
            UUID userId = requireUsable(lock(refreshCredential), clock.instant(), false).userId();
            if (replay(requestId, userId, "UPDATE_PROFILE", material).isPresent()) return null;
            Instant now = clock.instant();
            claim(requestId, userId, "UPDATE_PROFILE", material, "UPDATED", null, now);
            store.updateProfile(
                userId, profile.firstName(), profile.lastName(), profile.fatherName(), now);
            store.activateExternalOnboardingIfComplete(userId, now);
            return null;
          });
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  @Override
  public List<ProfileContactStore.ContactRecord> contacts(String refreshCredential) {
    return transactions.required(
        () -> {
          UUID userId = requireUsable(lock(refreshCredential), clock.instant(), false).userId();
          List<ProfileContactStore.ContactRecord> result =
              store.findContacts(userId, CONTACT_QUERY_LIMIT);
          if (result.size() > MAX_ACTIVE_CONTACTS) {
            throw error(ProfileError.CONTACT_LIMIT_REACHED);
          }
          return result;
        });
  }

  @Override
  public UUID addContact(
      String refreshCredential, UUID requestId, String type, String value, String locale) {
    requireRequestId(requestId);
    CanonicalContact contact = canonicalContact(type, value);
    RegistrationLocale registrationLocale = locale(locale);
    byte[] material = encoder.add(contact, registrationLocale);
    try {
      UUID contactId = UUID.randomUUID();
      return transactions.required(
          () -> {
            UUID userId = requireUsable(lock(refreshCredential), clock.instant(), false).userId();
            Optional<ProfileCommandRecord> prior =
                replay(requestId, userId, "ADD_CONTACT", material);
            if (prior.isPresent()) return prior.get().resultId();
            Instant now = clock.instant();
            store.lockUser(userId);
            store.lockContactKey(contact);
            if (store.countActiveContacts(userId) >= MAX_ACTIVE_CONTACTS) {
              throw error(ProfileError.CONTACT_LIMIT_REACHED);
            }
            if (store.contactKeyUnavailable(contact, null, now)) {
              throw error(ProfileError.CONTACT_CONFLICT);
            }
            PreparedContactChallenge prepared = prepare(contactId, contact, registrationLocale);
            claim(requestId, userId, "ADD_CONTACT", material, "ACCEPTED", contactId, now);
            store.insertContactChallenge(userId, prepared);
            return contactId;
          });
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  @Override
  public boolean resendContactVerification(
      String refreshCredential, UUID requestId, UUID contactId) {
    requireRequestId(requestId);
    requireContactId(contactId);
    byte[] material = encoder.resend(contactId);
    try {
      return transactions.required(
          () -> {
            UUID userId = requireUsable(lock(refreshCredential), clock.instant(), false).userId();
            Optional<ProfileCommandRecord> prior =
                replay(requestId, userId, "RESEND_CONTACT_VERIFICATION", material);
            if (prior.isPresent()) return "ACCEPTED".equals(prior.get().outcome());
            store.lockUser(userId);
            LockedContactChallenge previous =
                store
                    .lockLatestChallenge(userId, contactId)
                    .orElseThrow(() -> error(ProfileError.NOT_FOUND));
            Instant now = clock.instant();
            if ("USED".equals(previous.state())) return false;
            if (previous.lastSentAt().plus(RESEND_SPACING).isAfter(now)) {
              throw error(ProfileError.RESEND_TOO_SOON);
            }
            CanonicalContact contact =
                contactCanonicalizer.canonicalize(
                    channel(previous.channel()), previous.deliveryValue());
            store.lockContactKey(contact);
            if (store.contactKeyUnavailable(contact, contactId, now)) {
              throw error(ProfileError.CONTACT_CONFLICT);
            }
            PreparedContactChallenge prepared =
                prepare(contactId, contact, locale(previous.locale()));
            claim(
                requestId, userId, "RESEND_CONTACT_VERIFICATION", material, "ACCEPTED", null, now);
            store.replaceChallenge(previous, prepared);
            return true;
          });
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  @Override
  public boolean verifyContact(
      String refreshCredential, UUID requestId, UUID contactId, String code) {
    requireRequestId(requestId);
    requireContactId(contactId);
    if (code == null || !code.matches("[0-9]{8}")) throw error(ProfileError.INVALID_ARGUMENT);
    byte[] material = encoder.verify(contactId, code);
    try {
      return transactions.required(
          () -> {
            UUID userId = requireUsable(lock(refreshCredential), clock.instant(), false).userId();
            Optional<ProfileCommandRecord> prior =
                replay(requestId, userId, "VERIFY_CONTACT", material);
            if (prior.isPresent()) return "VERIFIED".equals(prior.get().outcome());
            store.lockUser(userId);
            LockedContactChallenge challenge =
                store
                    .lockLatestChallenge(userId, contactId)
                    .orElseThrow(() -> error(ProfileError.NOT_FOUND));
            Instant now = clock.instant();
            if (!"ACTIVE".equals(challenge.state()) || !challenge.expiresAt().isAfter(now)) {
              claim(requestId, userId, "VERIFY_CONTACT", material, "REJECTED_PROOF", null, now);
              return false;
            }
            if (!challenges.matches(
                challenge.challengeId(), code, challenge.verifier(), challenge.verifierKeyId())) {
              int failures = Math.min(5, challenge.failedAttempts() + 1);
              claim(requestId, userId, "VERIFY_CONTACT", material, "REJECTED_PROOF", null, now);
              store.recordFailedProof(challenge.challengeId(), failures, failures >= 5, now);
              return false;
            }
            CanonicalContact contact =
                contactCanonicalizer.canonicalize(
                    channel(challenge.channel()), challenge.deliveryValue());
            store.lockContactKey(contact);
            if (store.contactKeyUnavailable(contact, contactId, now)) {
              throw error(ProfileError.CONTACT_CONFLICT);
            }
            claim(requestId, userId, "VERIFY_CONTACT", material, "VERIFIED", null, now);
            store.confirmContact(challenge, now);
            store.activateExternalOnboardingIfComplete(userId, now);
            return true;
          });
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  @Override
  public boolean primary(String refreshCredential, UUID requestId, UUID contactId) {
    requireContactId(contactId);
    return contactMutation(
        refreshCredential,
        requestId,
        contactId,
        "SET_PRIMARY_CONTACT",
        encoder.primary(contactId),
        (userId, now) -> store.setPrimary(userId, contactId, now));
  }

  @Override
  public boolean remove(String refreshCredential, UUID requestId, UUID contactId) {
    requireContactId(contactId);
    return contactMutation(
        refreshCredential,
        requestId,
        contactId,
        "REMOVE_CONTACT",
        encoder.remove(contactId),
        (userId, now) -> store.remove(userId, contactId, now));
  }

  private boolean contactMutation(
      String refreshCredential,
      UUID requestId,
      UUID contactId,
      String operation,
      byte[] material,
      ContactMutation mutation) {
    requireRequestId(requestId);
    try {
      return transactions.required(
          () -> {
            Instant now = clock.instant();
            UUID userId = requireUsable(lock(refreshCredential), now, true).userId();
            Optional<ProfileCommandRecord> prior = replay(requestId, userId, operation, material);
            if (prior.isPresent()) return "APPLIED".equals(prior.get().outcome());
            store.lockUser(userId);
            boolean applied = mutation.apply(userId, now);
            if (!applied) throw error(ProfileError.NOT_FOUND);
            claim(requestId, userId, operation, material, "APPLIED", null, now);
            return true;
          });
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  private PreparedContactChallenge prepare(
      UUID contactId, CanonicalContact contact, RegistrationLocale locale) {
    Instant now = clock.instant();
    UUID challengeId = UUID.randomUUID();
    UUID outboxId = UUID.randomUUID();
    GeneratedChallenge generated = challenges.generate(challengeId);
    EncryptedHandoff handoff = escrow.encrypt(outboxId, contact, locale, generated.code());
    return new PreparedContactChallenge(
        contactId,
        challengeId,
        outboxId,
        UUID.randomUUID(),
        contact,
        locale,
        generated.verifier(),
        generated.keyId(),
        handoff,
        now,
        now.plus(CHALLENGE_TTL));
  }

  private Optional<ProfileCommandRecord> replay(
      UUID requestId, UUID userId, String operation, byte[] material) {
    Optional<ProfileCommandRecord> prior = store.findCommand(requestId);
    if (prior.isEmpty()) return prior;
    ProfileCommandRecord record = prior.get();
    CommandDedupRecord compatible =
        new CommandDedupRecord(
            record.requestId(),
            record.operation(),
            record.fingerprint(),
            record.fingerprintVersion(),
            record.fingerprintKeyId(),
            record.outcome(),
            record.createdAt());
    if (!userId.equals(record.userId())
        || !operation.equals(record.operation())
        || !fingerprints.matches(material, compatible)) {
      throw error(ProfileError.REQUEST_ID_CONFLICT);
    }
    return prior;
  }

  private void claim(
      UUID requestId,
      UUID userId,
      String operation,
      byte[] material,
      String outcome,
      UUID resultId,
      Instant now) {
    FingerprintDigest fingerprint = fingerprints.digest(material);
    if (!store.tryInsertCommand(
        requestId, userId, operation, fingerprint, outcome, resultId, now)) {
      replay(requestId, userId, operation, material)
          .orElseThrow(() -> error(ProfileError.REQUEST_ID_CONFLICT));
    }
  }

  private LockedRefreshCredential lock(String credential) {
    return lookup
        .lock(authenticationStore, credential)
        .orElseThrow(() -> error(ProfileError.INVALID_SESSION));
  }

  private static LockedRefreshCredential requireUsable(
      LockedRefreshCredential credential, Instant now, boolean recent) {
    boolean activeUser = "ACTIVE".equals(credential.userStatus());
    boolean externalOnboarding =
        "PENDING".equals(credential.userStatus())
            && credential.sessionMode()
                == com.sajtech.identity.application.authentication.model.AuthenticationSessionMode
                    .AUTHENTICATED_ONBOARDING;
    if (!"ACTIVE".equals(credential.credentialState())
        || !"ACTIVE".equals(credential.familyState())
        || (!activeUser && !externalOnboarding)
        || !now.isBefore(credential.idleExpiresAt())
        || !now.isBefore(credential.absoluteExpiresAt())) {
      throw error(ProfileError.INVALID_SESSION);
    }
    if (recent
        && (credential.authenticatedAt() == null
            || credential.authenticatedAt().plus(RECENT_AUTH_AGE).isBefore(now))) {
      throw error(ProfileError.RECENT_AUTHENTICATION_REQUIRED);
    }
    return credential;
  }

  private CanonicalContact canonicalContact(String type, String value) {
    try {
      return contactCanonicalizer.canonicalize(channel(type), value);
    } catch (RuntimeException exception) {
      throw error(ProfileError.INVALID_ARGUMENT);
    }
  }

  private static RegistrationChannel channel(String value) {
    try {
      return RegistrationChannel.valueOf(value);
    } catch (RuntimeException exception) {
      throw error(ProfileError.INVALID_ARGUMENT);
    }
  }

  private static RegistrationLocale locale(String value) {
    if ("en".equals(value)) return RegistrationLocale.EN;
    if ("fa".equals(value)) return RegistrationLocale.FA;
    throw error(ProfileError.INVALID_ARGUMENT);
  }

  private static void requireRequestId(UUID requestId) {
    if (requestId == null || requestId.version() != 4) throw error(ProfileError.INVALID_ARGUMENT);
  }

  private static void requireContactId(UUID contactId) {
    if (contactId == null) throw error(ProfileError.INVALID_ARGUMENT);
  }

  private static ProfileException error(ProfileError error) {
    return new ProfileException(error, error.name());
  }

  @FunctionalInterface
  private interface ContactMutation {
    boolean apply(UUID userId, Instant now);
  }
}
