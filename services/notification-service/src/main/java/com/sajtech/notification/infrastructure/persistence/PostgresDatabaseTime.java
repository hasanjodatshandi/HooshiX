package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.delivery.port.out.DeliveryDatabaseTimePort;
import com.sajtech.notification.application.submit.port.out.DatabaseTimePort;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class PostgresDatabaseTime implements DatabaseTimePort, DeliveryDatabaseTimePort {
  private final DSLContext dsl;

  public PostgresDatabaseTime(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public java.time.Instant now() {
    Record record = dsl.fetchOne("SELECT clock_timestamp() AS authoritative_now");
    OffsetDateTime value =
        record == null ? null : record.get("authoritative_now", OffsetDateTime.class);
    if (value == null) {
      throw new IllegalStateException("PostgreSQL did not return authoritative time");
    }
    return value.toInstant();
  }
}
