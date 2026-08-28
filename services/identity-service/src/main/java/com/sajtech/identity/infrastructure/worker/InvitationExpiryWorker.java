package com.sajtech.identity.infrastructure.worker;

import static com.sajtech.identity.application.transaction.model.TransactionProfile.MAINTENANCE;

import com.sajtech.identity.application.tenant.port.out.TenantStore;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import io.micrometer.core.instrument.*;
import java.time.Clock;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;

public final class InvitationExpiryWorker implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(InvitationExpiryWorker.class);
  private final TenantStore store;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final Counter expired;
  private final Counter failures;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("identity-invitation-expiry").factory());
  private volatile boolean running;

  public InvitationExpiryWorker(
      TenantStore store, TransactionRunner transactions, Clock clock, MeterRegistry meters) {
    this.store = store;
    this.transactions = transactions;
    this.clock = clock;
    this.expired = meters.counter("identity.invitation.expiry.expired");
    this.failures = meters.counter("identity.invitation.expiry.failures");
  }

  @Override
  public synchronized void start() {
    if (running) return;
    running = true;
    schedule(0);
  }

  @Override
  public synchronized void stop() {
    running = false;
    executor.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void schedule(long delaySeconds) {
    if (running) executor.schedule(this::cycle, delaySeconds, TimeUnit.SECONDS);
  }

  private void cycle() {
    boolean busy = false;
    try {
      int expired =
          transactions.required(MAINTENANCE, () -> store.expireInvitations(clock.instant(), 200));
      busy = expired == 200;
      increment(this.expired, expired);
    } catch (RuntimeException failure) {
      increment(failures, 1);
      LOG.atWarn()
          .addKeyValue("eventCode", "IDENTITY_INVITATION_EXPIRY_CYCLE_FAILED")
          .log("Invitation expiry cycle failed");
    } finally {
      schedule(busy ? 1 : 60);
    }
  }

  private static void increment(Counter counter, double amount) {
    try {
      counter.increment(amount);
    } catch (RuntimeException ignored) {
      // Ordinary telemetry failure must not change durable invitation expiry.
    }
  }
}
