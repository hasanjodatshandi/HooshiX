package com.sajtech.identity.infrastructure.worker;

import static com.sajtech.identity.application.transaction.model.TransactionProfile.WORK_CLAIM;

import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.model.AuthorizationOutboxItem;
import com.sajtech.identity.application.tenant.port.out.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.infrastructure.observability.AuthorizationOutboxMetrics;
import com.sajtech.identity.infrastructure.persistence.AuthorizationOutboxTelemetryQuery;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;

public final class AuthorizationOutboxDispatcher implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationOutboxDispatcher.class);
  private static final int BATCH = 32;
  private static final Duration LEASE = Duration.ofSeconds(30);
  private final TenantStore store;
  private final AuthorizationOutboxTelemetryQuery telemetryQuery;
  private final AuthorizationTenantPort authorization;
  private final TransactionRunner tx;
  private final Clock clock;
  private final AuthorizationOutboxMetrics metrics;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("identity-authorization-outbox").factory());
  private volatile boolean running;

  public AuthorizationOutboxDispatcher(
      TenantStore store,
      AuthorizationOutboxTelemetryQuery telemetryQuery,
      AuthorizationTenantPort authorization,
      TransactionRunner tx,
      Clock clock,
      AuthorizationOutboxMetrics metrics) {
    this.store = store;
    this.telemetryQuery = telemetryQuery;
    this.authorization = authorization;
    this.tx = tx;
    this.clock = clock;
    this.metrics = metrics;
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
      busy = dispatchDue();
    } catch (RuntimeException e) {
      LOG.atWarn()
          .addKeyValue("eventCode", "IDENTITY_AUTHORIZATION_OUTBOX_CYCLE_FAILED")
          .log("Authorization outbox cycle failed");
    } finally {
      sampleMetrics();
      schedule(busy ? 250 : 1000);
    }
  }

  boolean dispatchDue() {
    boolean claimed = false;
    for (int index = 0; index < BATCH; index++) {
      Instant now = clock.instant();
      List<AuthorizationOutboxItem> items =
          tx.required(WORK_CLAIM, () -> store.claimAuthorizationOutbox(now, 1, now.plus(LEASE)));
      if (items.isEmpty()) break;
      if (items.size() != 1) {
        throw new IllegalStateException("Authorization outbox exceeded the single-claim lease");
      }
      claimed = true;
      dispatch(items.getFirst());
    }
    return claimed;
  }

  private void sampleMetrics() {
    Instant now = clock.instant();
    if (!metrics.sampleDue(now)) return;
    try {
      metrics.recordOldestPending(
          telemetryQuery.oldestUnresolvedAuthorizationOutboxCreatedAt().orElse(null), now);
    } catch (RuntimeException ignored) {
      // Ordinary telemetry sampling must not change durable outbox processing.
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
    if (definitive) {
      metrics.definitiveFailure(i.operation());
      LOG.atError()
          .addKeyValue("eventCode", "IDENTITY_AUTHORIZATION_OUTBOX_DEFINITIVE_FAILURE")
          .addKeyValue("operation", i.operation())
          .log("Authorization outbox command failed definitively");
    }
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
