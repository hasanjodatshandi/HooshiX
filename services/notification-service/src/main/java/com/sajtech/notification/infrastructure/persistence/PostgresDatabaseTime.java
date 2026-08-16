package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.submit.port.out.DatabaseTimePort;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jooq.Record1;

public final class PostgresDatabaseTime implements DatabaseTimePort {
  private final DSLContext dsl;

  public PostgresDatabaseTime(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public java.time.Instant now() {
    Record1<OffsetDateTime> record =
        dsl.fetchOne("SELECT clock_timestamp()::timestamptz", OffsetDateTime.class);
    if (record == null || record.value1() == null) {
      throw new IllegalStateException("PostgreSQL did not return authoritative time");
    }
    return record.value1().toInstant();
  }
}
