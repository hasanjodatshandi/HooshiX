package com.sajtech.identity.application.notificationhandoff;

import com.sajtech.identity.application.notificationhandoff.model.OutboxClaim;
import com.sajtech.identity.application.notificationhandoff.port.out.NotificationHandoffPort;
import com.sajtech.identity.application.notificationhandoff.port.out.NotificationOutboxPort;
import com.sajtech.identity.application.registration.OutboxPayload;
import com.sajtech.identity.application.registration.port.out.RegistrationCryptoPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;

public final class NotificationOutboxDispatcher {
  private static final int BATCH_SIZE = 32;
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration CUTOFF_MARGIN = Duration.ofSeconds(5);
  private static final Duration MAX_ESCROW_AGE = Duration.ofHours(24);
  private static final long[] BASE_DELAYS_MILLIS = {1000, 2000, 5000, 10_000, 30_000};

  private final NotificationOutboxPort outbox;
  private final NotificationHandoffPort handoff;
  private final RegistrationCryptoPort crypto;
  private final Clock clock;

  public NotificationOutboxDispatcher(
      NotificationOutboxPort outbox,
      NotificationHandoffPort handoff,
      RegistrationCryptoPort crypto,
      Clock clock) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.handoff = Objects.requireNonNull(handoff, "handoff");
    this.crypto = Objects.requireNonNull(crypto, "crypto");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public int dispatchBatch() {
    int processed = 0;
    while (processed < BATCH_SIZE) {
      Instant now = now();
      var claim = outbox.claim(now, now.plus(LEASE));
      if (claim.isEmpty()) {
        return processed;
      }
      dispatch(claim.get(), now);
      processed++;
    }
    return processed;
  }

  private void dispatch(OutboxClaim claim, Instant now) {
    if (!now.isBefore(claim.messageNotAfter().minus(CUTOFF_MARGIN))
        || now.isAfter(claim.createdAt().plus(MAX_ESCROW_AGE))) {
      outbox.failPermanently(claim.outboxId(), "HANDOFF_CUTOFF_REACHED", now);
      return;
    }

    byte[] plaintext;
    try {
      plaintext = crypto.decryptCallerEscrow(claim.outboxId(), claim.escrow());
    } catch (RuntimeException exception) {
      outbox.failPermanently(claim.outboxId(), "CALLER_ESCROW_CORRUPT", now);
      return;
    }

    try {
      OutboxPayload payload = OutboxPayload.decode(plaintext);
      if (!payload.messageNotAfter().equals(claim.messageNotAfter())) {
        outbox.failPermanently(claim.outboxId(), "CALLER_ESCROW_CONTEXT_MISMATCH", now);
        return;
      }
      handoff.submit(claim.requestId(), payload);
      outbox.acknowledge(claim.outboxId(), now());
    } catch (NotificationHandoffException exception) {
      Instant current = now();
      if (!exception.retryable()) {
        outbox.failPermanently(claim.outboxId(), exception.machineCode(), current);
      } else {
        outbox.retry(
            claim.outboxId(),
            exception.machineCode(),
            nextAttempt(claim, current),
            current);
      }
    } catch (IllegalArgumentException exception) {
      outbox.failPermanently(claim.outboxId(), "CALLER_ESCROW_CORRUPT", now());
    } finally {
      Arrays.fill(plaintext, (byte) 0);
    }
  }

  private static Instant nextAttempt(OutboxClaim claim, Instant now) {
    int index = Math.min(Math.max(claim.attemptCount() - 1, 0), BASE_DELAYS_MILLIS.length - 1);
    long base = BASE_DELAYS_MILLIS[index];
    long spread = Math.max(1, base / 5);
    long deterministic =
        Math.floorMod(claim.outboxId().getLeastSignificantBits(), spread * 2 + 1) - spread;
    return now.plusMillis(base + deterministic);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
