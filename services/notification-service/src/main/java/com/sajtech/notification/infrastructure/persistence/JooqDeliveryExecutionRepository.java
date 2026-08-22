package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.port.out.DeliveryExecutionRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqDeliveryExecutionRepository implements DeliveryExecutionRepository {
  private final DSLContext dsl;

  public JooqDeliveryExecutionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void recordOutcome(ProviderDispatchMessage message, ProviderDispatchOutcome outcome) {
    dsl.execute("""
        INSERT INTO provider_receipt_evidence
        (receipt_id, notification_id, attempt_id, provider_code, provider_correlation_id, observed_at, expires_at)
        VALUES (?, ?, ?, ?, ?, clock_timestamp(), clock_timestamp() + interval '1 day')
        """, UUID.randomUUID(), message.notificationId(), message.executionId(),
        outcome.providerCode(), outcome.providerCorrelationId());
  }

  @Override
  public void scheduleRetry(ProviderDispatchMessage message, Duration delay) {
    dsl.execute("""
        UPDATE notification_attempt
        SET state='RETRY_WAIT', next_action_at=clock_timestamp() + (? * interval '1 millisecond'), claimed_until=NULL, updated_at=clock_timestamp()
        WHERE notification_id=? AND execution_id=?
        """, delay.toMillis(), message.notificationId(), message.executionId());
  }

  @Override
  public void markPermanentFailure(ProviderDispatchMessage message) {
    dsl.execute("""
        UPDATE notification_attempt
        SET state='FAILED_PERMANENT', claimed_until=NULL, updated_at=clock_timestamp()
        WHERE notification_id=? AND execution_id=?
        """, message.notificationId(), message.executionId());
  }
}
