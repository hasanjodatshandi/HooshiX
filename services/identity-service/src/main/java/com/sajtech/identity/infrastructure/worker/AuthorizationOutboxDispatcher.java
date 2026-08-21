package com.sajtech.identity.infrastructure.worker;

import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.model.AuthorizationOutboxItem;
import com.sajtech.identity.application.tenant.port.out.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;

public final class AuthorizationOutboxDispatcher implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationOutboxDispatcher.class);
  private final TenantStore store;
  private final AuthorizationTenantPort authorization;
  private final TransactionRunner tx;
  private final Clock clock;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("identity-authorization-outbox").factory());
  private volatile boolean running;

  public AuthorizationOutboxDispatcher(
      TenantStore store, AuthorizationTenantPort authorization, TransactionRunner tx, Clock clock) {
    this.store = store;
    this.authorization = authorization;
    this.tx = tx;
    this.clock = clock;
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

  private void schedule(long ms) {
    if (running) executor.schedule(this::cycle, ms, TimeUnit.MILLISECONDS);
  }

  private void cycle() {
    boolean busy = false;
    try {
      Instant now = clock.instant();
      List<AuthorizationOutboxItem> items =
          tx.required(() -> store.claimAuthorizationOutbox(now, 32, now.plusSeconds(30)));
      busy = !items.isEmpty();
      for (var item : items) dispatch(item);
    } catch (RuntimeException e) {
      LOG.atWarn()
          .addKeyValue("eventCode", "IDENTITY_AUTHORIZATION_OUTBOX_CYCLE_FAILED")
          .log("Authorization outbox cycle failed");
    } finally {
      schedule(busy ? 250 : 1000);
    }
  }

  private void dispatch(AuthorizationOutboxItem i) {
    try {
      switch (i.operation()) {
        case "PROVISION_OWNER" ->
            authorization.provisionOwner(i.requestId(), i.tenantId(), i.membershipId(), i.userId());
        case "PROVISION_MEMBER" ->
            authorization.provisionMember(
                i.requestId(), i.tenantId(), i.membershipId(), i.userId());
        case "APPLY_TENANT_LIFECYCLE" ->
            authorization.applyTenantLifecycle(i.requestId(), i.tenantId(), i.lifecycle());
        case "FINALIZE_MEMBERSHIP_REMOVAL" ->
            authorization.finalizeMembershipRemoval(i.requestId(), i.tenantId(), i.membershipId());
        case "CANCEL_MEMBERSHIP_REMOVAL" ->
            authorization.cancelMembershipRemoval(i.requestId(), i.tenantId(), i.membershipId());
        default -> throw new IllegalStateException("Unknown Authorization outbox operation");
      }
      Instant now = clock.instant();
      tx.required(
          () -> {
            store.completeAuthorizationOutbox(i.outboxId(), now);
            return null;
          });
    } catch (TenantException e) {
      boolean definitive =
          e.error() == TenantError.REQUEST_ID_CONFLICT
              || e.error() == TenantError.AUTHORIZATION_DENIED
              || e.error() == TenantError.LAST_TENANT_OWNER;
      retry(i, definitive);
    } catch (RuntimeException e) {
      retry(i, false);
    }
  }

  private void retry(AuthorizationOutboxItem i, boolean definitive) {
    int attempt = i.attemptCount() + 1;
    Instant now = clock.instant();
    Duration delay = delay(attempt);
    tx.required(
        () -> {
          store.rescheduleAuthorizationOutbox(
              i.outboxId(), now, now.plus(delay), attempt, definitive);
          return null;
        });
    if (definitive)
      LOG.atError()
          .addKeyValue("eventCode", "IDENTITY_AUTHORIZATION_OUTBOX_DEFINITIVE_FAILURE")
          .addKeyValue("operation", i.operation())
          .log("Authorization outbox command failed definitively");
  }

  private static Duration delay(int attempt) {
    long ms =
        switch (attempt) {
          case 1 -> 1000;
          case 2 -> 5000;
          case 3 -> 30000;
          case 4 -> 120000;
          case 5 -> 600000;
          default -> 600000;
        };
    if (attempt > 5) {
      long delta = ThreadLocalRandom.current().nextLong(-120000, 120001);
      ms = Math.max(480000, Math.min(720000, ms + delta));
    }
    return Duration.ofMillis(ms);
  }
}
