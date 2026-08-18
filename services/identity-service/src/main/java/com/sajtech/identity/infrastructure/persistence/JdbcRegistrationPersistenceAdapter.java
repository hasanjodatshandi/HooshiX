package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.notificationhandoff.model.OutboxClaim;
import com.sajtech.identity.application.notificationhandoff.port.out.NotificationOutboxPort;
import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.model.ChallengeVerifier;
import com.sajtech.identity.application.registration.model.ConfirmWrite;
import com.sajtech.identity.application.registration.model.EscrowCiphertext;
import com.sajtech.identity.application.registration.model.IdempotencyRecord;
import com.sajtech.identity.application.registration.model.PendingRegistrationSnapshot;
import com.sajtech.identity.application.registration.model.RegistrationWrite;
import com.sajtech.identity.application.registration.model.RequestFingerprint;
import com.sajtech.identity.application.registration.model.RequestPurpose;
import com.sajtech.identity.application.registration.model.ResendWrite;
import com.sajtech.identity.application.registration.port.out.RegistrationPersistencePort;
import com.sajtech.identity.domain.registration.CanonicalContact;
import com.sajtech.identity.domain.registration.ContactKind;
import com.sajtech.identity.domain.registration.RegistrationLocale;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcRegistrationPersistenceAdapter
    implements RegistrationPersistencePort, NotificationOutboxPort {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcRegistrationPersistenceAdapter(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  @Override
  public Optional<IdempotencyRecord> findIdempotency(UUID requestId, RequestPurpose purpose) {
    List<IdempotencyRecord> rows =
        jdbc.query(
            "SELECT fingerprint_version, fingerprint_key_id, fingerprint_digest, outcome "
                + "FROM identity_request_idempotency WHERE request_id = ? AND purpose = ?",
            (rs, rowNum) -> idempotency(rs),
            requestId,
            purpose.name());
    return rows.stream().findFirst();
  }

  @Override
  public void createOrContinue(RegistrationWrite write) {
    try {
      transactions.executeWithoutResult(status -> createOrContinueTransaction(write));
    } catch (DuplicateKeyException exception) {
      transactions.executeWithoutResult(status -> settleRegistrationRace(write));
    }
  }

  private void createOrContinueTransaction(RegistrationWrite write) {
    Optional<IdempotencyRecord> existing = findIdempotency(write.requestId(), RequestPurpose.REGISTER);
    if (existing.isPresent()) {
      requireSame(existing.get(), write.fingerprint());
      return;
    }

    List<ContactRow> contacts =
        jdbc.query(
            "SELECT contact_id, user_id, verified_at, reservation_expires_at "
                + "FROM identity_contact WHERE kind = ? AND canonical_value = ? FOR UPDATE",
            (rs, rowNum) ->
                new ContactRow(
                    rs.getObject("contact_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    instantOrNull(rs, "verified_at"),
                    instant(rs, "reservation_expires_at")),
            write.contact().kind().name(),
            write.contact().canonicalValue());

    if (!contacts.isEmpty()) {
      ContactRow existingContact = contacts.getFirst();
      if (existingContact.verifiedAt() != null
          || existingContact.reservationExpiresAt().isAfter(write.createdAt())) {
        insertIdempotency(
            write.requestId(), RequestPurpose.REGISTER, write.fingerprint(), "ACCEPTED", write.createdAt());
        return;
      }
      jdbc.update("DELETE FROM identity_user WHERE user_id = ?", existingContact.userId());
    }

    jdbc.update(
        "INSERT INTO identity_user(user_id, status, created_at, updated_at) VALUES (?, 'PENDING', ?, ?)",
        write.userId(),
        timestamp(write.createdAt()),
        timestamp(write.createdAt()));
    jdbc.update(
        "INSERT INTO identity_profile(user_id, first_name, last_name, father_name) VALUES (?, ?, ?, ?)",
        write.userId(),
        write.profile().firstName(),
        write.profile().lastName(),
        write.profile().fatherName());
    jdbc.update(
        "INSERT INTO identity_contact(contact_id, user_id, kind, canonical_value, delivery_value, "
            + "reservation_expires_at, verified_at, primary_contact, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, NULL, FALSE, ?)",
        write.contactId(),
        write.userId(),
        write.contact().kind().name(),
        write.contact().canonicalValue(),
        write.contact().deliveryValue(),
        timestamp(write.challengeExpiresAt()),
        timestamp(write.createdAt()));
    jdbc.update(
        "INSERT INTO identity_credential(user_id, password_hash, created_at) VALUES (?, ?, ?)",
        write.userId(),
        write.passwordHash(),
        timestamp(write.createdAt()));
    insertChallenge(
        write.challengeId(),
        write.userId(),
        write.contactId(),
        write.locale(),
        write.verifier(),
        write.challengeExpiresAt(),
        write.resendNotBefore(),
        write.createdAt());
    insertOutbox(
        write.outboxId(),
        write.notificationRequestId(),
        write.userId(),
        write.escrow(),
        write.challengeExpiresAt(),
        write.createdAt());
    insertIdempotency(
        write.requestId(), RequestPurpose.REGISTER, write.fingerprint(), "ACCEPTED", write.createdAt());
  }

  private void settleRegistrationRace(RegistrationWrite write) {
    Optional<IdempotencyRecord> existing = findIdempotency(write.requestId(), RequestPurpose.REGISTER);
    if (existing.isPresent()) {
      requireSame(existing.get(), write.fingerprint());
      return;
    }
    Integer contactCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM identity_contact WHERE kind = ? AND canonical_value = ?",
            Integer.class,
            write.contact().kind().name(),
            write.contact().canonicalValue());
    if (contactCount == null || contactCount < 1) {
      throw new RegistrationException(RegistrationError.REGISTRATION_UNAVAILABLE);
    }
    insertIdempotency(
        write.requestId(), RequestPurpose.REGISTER, write.fingerprint(), "ACCEPTED", write.createdAt());
  }

  @Override
  public Optional<PendingRegistrationSnapshot> findPending(CanonicalContact contact, Instant now) {
    List<PendingRegistrationSnapshot> rows =
        jdbc.query(
            "SELECT c.user_id, c.contact_id, c.kind, c.delivery_value, ch.challenge_id, ch.locale, "
                + "ch.verifier_key_id, ch.verifier_digest, ch.expires_at, ch.resend_not_before, ch.failed_attempts "
                + "FROM identity_contact c JOIN identity_registration_challenge ch ON ch.contact_id = c.contact_id "
                + "WHERE c.kind = ? AND c.canonical_value = ? AND c.verified_at IS NULL "
                + "AND c.reservation_expires_at > ? AND ch.consumed_at IS NULL AND ch.expires_at > ? "
                + "ORDER BY ch.created_at DESC LIMIT 1",
            (rs, rowNum) -> pending(rs),
            contact.kind().name(),
            contact.canonicalValue(),
            timestamp(now),
            timestamp(now));
    return rows.stream().findFirst();
  }

  @Override
  public void recordNeutralAcceptance(
      UUID requestId, RequestPurpose purpose, RequestFingerprint fingerprint, Instant now) {
    transactions.executeWithoutResult(
        status -> {
          Optional<IdempotencyRecord> existing = findIdempotency(requestId, purpose);
          if (existing.isPresent()) {
            requireSame(existing.get(), fingerprint);
            return;
          }
          insertIdempotency(requestId, purpose, fingerprint, "ACCEPTED", now);
        });
  }

  @Override
  public boolean replaceChallenge(ResendWrite write) {
    Boolean result =
        transactions.execute(
            status -> {
              Optional<IdempotencyRecord> existing =
                  findIdempotency(
                      write.requestId(), RequestPurpose.RESEND_REGISTRATION_VERIFICATION);
              if (existing.isPresent()) {
                requireSame(existing.get(), write.fingerprint());
                return true;
              }
              int invalidated =
                  jdbc.update(
                      "UPDATE identity_registration_challenge SET consumed_at = ? "
                          + "WHERE challenge_id = ? AND consumed_at IS NULL AND expires_at > ? "
                          + "AND resend_not_before <= ?",
                      timestamp(write.createdAt()),
                      write.expected().challengeId(),
                      timestamp(write.createdAt()),
                      timestamp(write.createdAt()));
              if (invalidated != 1) {
                return false;
              }
              jdbc.update(
                  "UPDATE identity_contact SET reservation_expires_at = ? WHERE contact_id = ? AND verified_at IS NULL",
                  timestamp(write.challengeExpiresAt()),
                  write.expected().contactId());
              insertChallenge(
                  write.replacementChallengeId(),
                  write.expected().userId(),
                  write.expected().contactId(),
                  write.expected().locale(),
                  write.verifier(),
                  write.challengeExpiresAt(),
                  write.resendNotBefore(),
                  write.createdAt());
              insertOutbox(
                  write.outboxId(),
                  write.notificationRequestId(),
                  write.expected().userId(),
                  write.escrow(),
                  write.challengeExpiresAt(),
                  write.createdAt());
              insertIdempotency(
                  write.requestId(),
                  RequestPurpose.RESEND_REGISTRATION_VERIFICATION,
                  write.fingerprint(),
                  "ACCEPTED",
                  write.createdAt());
              return true;
            });
    return Boolean.TRUE.equals(result);
  }

  @Override
  public boolean recordFailedAttempt(PendingRegistrationSnapshot expected, Instant now) {
    int updated =
        jdbc.update(
            "UPDATE identity_registration_challenge SET failed_attempts = failed_attempts + 1 "
                + "WHERE challenge_id = ? AND failed_attempts = ? AND failed_attempts < 5 "
                + "AND consumed_at IS NULL AND expires_at > ?",
            expected.challengeId(),
            expected.failedAttempts(),
            timestamp(now));
    return updated == 1;
  }

  @Override
  public boolean confirm(ConfirmWrite write) {
    Boolean result =
        transactions.execute(
            status -> {
              Optional<IdempotencyRecord> existing =
                  findIdempotency(write.requestId(), RequestPurpose.CONFIRM_REGISTRATION);
              if (existing.isPresent()) {
                requireSame(existing.get(), write.fingerprint());
                return true;
              }
              int consumed =
                  jdbc.update(
                      "UPDATE identity_registration_challenge SET consumed_at = ? "
                          + "WHERE challenge_id = ? AND failed_attempts = ? AND failed_attempts < 5 "
                          + "AND consumed_at IS NULL AND expires_at > ?",
                      timestamp(write.confirmedAt()),
                      write.expected().challengeId(),
                      write.expected().failedAttempts(),
                      timestamp(write.confirmedAt()));
              if (consumed != 1) {
                return false;
              }
              int verified =
                  jdbc.update(
                      "UPDATE identity_contact SET verified_at = ?, primary_contact = TRUE, "
                          + "reservation_expires_at = ? WHERE contact_id = ? AND verified_at IS NULL",
                      timestamp(write.confirmedAt()),
                      timestamp(write.confirmedAt()),
                      write.expected().contactId());
              if (verified != 1) {
                return false;
              }
              int activated =
                  jdbc.update(
                      "UPDATE identity_user u SET status = 'ACTIVE', updated_at = ? WHERE user_id = ? "
                          + "AND status = 'PENDING' "
                          + "AND EXISTS (SELECT 1 FROM identity_profile p WHERE p.user_id = u.user_id) "
                          + "AND EXISTS (SELECT 1 FROM identity_credential cr WHERE cr.user_id = u.user_id) "
                          + "AND EXISTS (SELECT 1 FROM identity_contact c WHERE c.user_id = u.user_id AND c.verified_at IS NOT NULL)",
                      timestamp(write.confirmedAt()),
                      write.expected().userId());
              if (activated != 1) {
                throw new RegistrationException(RegistrationError.REGISTRATION_UNAVAILABLE);
              }
              insertIdempotency(
                  write.requestId(),
                  RequestPurpose.CONFIRM_REGISTRATION,
                  write.fingerprint(),
                  "CONFIRMED",
                  write.confirmedAt());
              return true;
            });
    return Boolean.TRUE.equals(result);
  }

  @Override
  public Optional<OutboxClaim> claim(Instant now, Instant leaseUntil) {
    return transactions.execute(
        status -> {
          List<OutboxClaim> rows =
              jdbc.query(
                  "SELECT outbox_id, request_id, escrow_key_id, escrow_nonce, escrow_ciphertext, "
                      + "message_not_after, attempt_count, created_at FROM identity_notification_outbox "
                      + "WHERE ((state = 'PENDING') OR (state = 'PROCESSING' AND lease_until <= ?)) "
                      + "AND next_attempt_at <= ? ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                  (rs, rowNum) -> outbox(rs),
                  timestamp(now),
                  timestamp(now));
          if (rows.isEmpty()) {
            return Optional.empty();
          }
          OutboxClaim claim = rows.getFirst();
          int updated =
              jdbc.update(
                  "UPDATE identity_notification_outbox SET state = 'PROCESSING', attempt_count = attempt_count + 1, "
                      + "lease_until = ?, updated_at = ? WHERE outbox_id = ?",
                  timestamp(leaseUntil),
                  timestamp(now),
                  claim.outboxId());
          if (updated != 1) {
            return Optional.empty();
          }
          return Optional.of(
              new OutboxClaim(
                  claim.outboxId(),
                  claim.requestId(),
                  claim.escrow(),
                  claim.messageNotAfter(),
                  claim.attemptCount() + 1,
                  claim.createdAt()));
        });
  }

  @Override
  public void acknowledge(UUID outboxId, Instant now) {
    terminal(outboxId, "ACKNOWLEDGED", null, now);
  }

  @Override
  public void retry(UUID outboxId, String machineCode, Instant nextAttemptAt, Instant now) {
    jdbc.update(
        "UPDATE identity_notification_outbox SET state = 'PENDING', lease_until = NULL, "
            + "last_error_code = ?, next_attempt_at = ?, updated_at = ? WHERE outbox_id = ? AND state = 'PROCESSING'",
        machineCode,
        timestamp(nextAttemptAt),
        timestamp(now),
        outboxId);
  }

  @Override
  public void failPermanently(UUID outboxId, String machineCode, Instant now) {
    terminal(outboxId, "HANDOFF_FAILED", machineCode, now);
  }

  private void terminal(UUID outboxId, String state, String machineCode, Instant now) {
    jdbc.update(
        "UPDATE identity_notification_outbox SET state = ?, lease_until = NULL, last_error_code = ?, "
            + "escrow_key_id = NULL, escrow_nonce = NULL, escrow_ciphertext = NULL, updated_at = ? "
            + "WHERE outbox_id = ? AND state IN ('PENDING', 'PROCESSING')",
        state,
        machineCode,
        timestamp(now),
        outboxId);
  }

  private void insertChallenge(
      UUID challengeId,
      UUID userId,
      UUID contactId,
      RegistrationLocale locale,
      ChallengeVerifier verifier,
      Instant expiresAt,
      Instant resendNotBefore,
      Instant createdAt) {
    jdbc.update(
        "INSERT INTO identity_registration_challenge(challenge_id, user_id, contact_id, locale, verifier_key_id, "
            + "verifier_digest, expires_at, resend_not_before, failed_attempts, consumed_at, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?)",
        challengeId,
        userId,
        contactId,
        locale.wireValue(),
        verifier.keyId(),
        verifier.digest(),
        timestamp(expiresAt),
        timestamp(resendNotBefore),
        timestamp(createdAt));
  }

  private void insertOutbox(
      UUID outboxId,
      UUID requestId,
      UUID userId,
      EscrowCiphertext escrow,
      Instant messageNotAfter,
      Instant now) {
    jdbc.update(
        "INSERT INTO identity_notification_outbox(outbox_id, request_id, user_id, escrow_key_id, escrow_nonce, "
            + "escrow_ciphertext, message_not_after, state, attempt_count, next_attempt_at, lease_until, "
            + "last_error_code, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, NULL, NULL, ?, ?)",
        outboxId,
        requestId,
        userId,
        escrow.keyId(),
        escrow.nonce(),
        escrow.ciphertext(),
        timestamp(messageNotAfter),
        timestamp(now),
        timestamp(now),
        timestamp(now));
  }

  private void insertIdempotency(
      UUID requestId,
      RequestPurpose purpose,
      RequestFingerprint fingerprint,
      String outcome,
      Instant now) {
    jdbc.update(
        "INSERT INTO identity_request_idempotency(request_id, purpose, fingerprint_version, fingerprint_key_id, "
            + "fingerprint_digest, outcome, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        requestId,
        purpose.name(),
        fingerprint.version(),
        fingerprint.keyId(),
        fingerprint.digest(),
        outcome,
        timestamp(now));
  }

  private static void requireSame(IdempotencyRecord existing, RequestFingerprint candidate) {
    RequestFingerprint stored = existing.fingerprint();
    if (stored.version() != candidate.version()
        || !stored.keyId().equals(candidate.keyId())
        || !MessageDigest.isEqual(stored.digest(), candidate.digest())) {
      throw new RegistrationException(RegistrationError.REQUEST_ID_CONFLICT);
    }
  }

  private static IdempotencyRecord idempotency(ResultSet rs) throws SQLException {
    return new IdempotencyRecord(
        new RequestFingerprint(
            rs.getShort("fingerprint_version"),
            rs.getString("fingerprint_key_id"),
            rs.getBytes("fingerprint_digest")),
        rs.getString("outcome"));
  }

  private static PendingRegistrationSnapshot pending(ResultSet rs) throws SQLException {
    return new PendingRegistrationSnapshot(
        rs.getObject("user_id", UUID.class),
        rs.getObject("contact_id", UUID.class),
        rs.getObject("challenge_id", UUID.class),
        ContactKind.valueOf(rs.getString("kind")),
        rs.getString("delivery_value"),
        switch (rs.getString("locale")) {
          case "fa" -> RegistrationLocale.FA;
          case "en" -> RegistrationLocale.EN;
          default -> throw new SQLException("unsupported persisted registration locale");
        },
        new ChallengeVerifier(rs.getString("verifier_key_id"), rs.getBytes("verifier_digest")),
        instant(rs, "expires_at"),
        instant(rs, "resend_not_before"),
        rs.getInt("failed_attempts"));
  }

  private static OutboxClaim outbox(ResultSet rs) throws SQLException {
    return new OutboxClaim(
        rs.getObject("outbox_id", UUID.class),
        rs.getObject("request_id", UUID.class),
        new EscrowCiphertext(
            rs.getString("escrow_key_id"), rs.getBytes("escrow_nonce"), rs.getBytes("escrow_ciphertext")),
        instant(rs, "message_not_after"),
        rs.getInt("attempt_count"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private record ContactRow(
      UUID contactId, UUID userId, Instant verifiedAt, Instant reservationExpiresAt) {}
}
