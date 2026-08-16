package com.sajtech.notification.infrastructure.observability;

import com.sajtech.notification.application.submit.port.out.NotificationTemplateCatalog;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import org.jooq.DSLContext;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class NotificationReadinessHealthIndicator implements HealthIndicator {
  private final DSLContext dsl;
  private final NotificationTemplateCatalog templates;
  private final FileBackedKeyRing fingerprintKeyRing;
  private final FileBackedKeyRing deliveryKeyRing;

  public NotificationReadinessHealthIndicator(
      DSLContext dsl,
      NotificationTemplateCatalog templates,
      FileBackedKeyRing fingerprintKeyRing,
      FileBackedKeyRing deliveryKeyRing) {
    this.dsl = dsl;
    this.templates = templates;
    this.fingerprintKeyRing = fingerprintKeyRing;
    this.deliveryKeyRing = deliveryKeyRing;
  }

  @Override
  public Health health() {
    try {
      if (dsl.fetchOne("SELECT 1") == null) {
        return Health.down().withDetail("category", "DATABASE_UNAVAILABLE").build();
      }
      if (!fingerprintKeyRing.isFresh() || !deliveryKeyRing.isFresh()) {
        return Health.down().withDetail("category", "KEY_RING_STALE").build();
      }
      for (NotificationChannel channel : NotificationChannel.values()) {
        for (String locale : new String[] {"en", "fa"}) {
          if (templates
              .findActive(
                  channel, NotificationSemanticType.REGISTRATION_VERIFICATION_CODE, locale)
              .isEmpty()) {
            return Health.down().withDetail("category", "TEMPLATE_NOT_ACTIVE").build();
          }
        }
      }
      return Health.up().build();
    } catch (RuntimeException ignored) {
      return Health.down().withDetail("category", "NOTIFICATION_DEPENDENCY_UNAVAILABLE").build();
    }
  }
}
