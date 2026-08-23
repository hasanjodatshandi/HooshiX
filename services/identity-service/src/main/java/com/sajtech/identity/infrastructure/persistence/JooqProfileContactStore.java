package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.profile.port.out.ProfileContactStore;
import java.time.Instant;
import java.util.*;
import org.jooq.DSLContext;

public final class JooqProfileContactStore implements ProfileContactStore {
  private final DSLContext dsl;

  public JooqProfileContactStore(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  @Override
  public ProfileRecord findProfile(UUID userId) {
    var r = dsl.fetchOne("SELECT user_id,first_name,last_name,father_name FROM identity_profile WHERE user_id=?", userId);
    if (r == null) return null;
    return new ProfileRecord(r.get("user_id", UUID.class), r.get("first_name", String.class), r.get("last_name", String.class), r.get("father_name", String.class));
  }

  @Override
  public void updateProfile(UUID userId, String firstName, String lastName, String fatherName) {
    dsl.execute("UPDATE identity_profile SET first_name=?,last_name=?,father_name=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE user_id=?", firstName, lastName, fatherName, Instant.now(), userId);
  }

  @Override
  public List<ContactRecord> findContacts(UUID userId) {
    return dsl.fetch("SELECT contact_id,contact_type,delivery_value,verified_at,primary_active FROM identity_contact WHERE user_id=? AND removed_at IS NULL", userId)
        .map(r -> new ContactRecord(r.get("contact_id", UUID.class), r.get("contact_type", String.class), r.get("delivery_value", String.class), r.get("verified_at") != null, Boolean.TRUE.equals(r.get("primary_active", Boolean.class))));
  }

  @Override public UUID addContact(UUID userId, String type, String value) {
    UUID id = UUID.randomUUID(); Instant now = Instant.now();
    dsl.execute("INSERT INTO identity_contact(contact_id,user_id,contact_type,canonical_value,delivery_value,created_at,updated_at) VALUES (?,?,?,?,?,?,?)", id, userId, type, value.toLowerCase(), value, now, now);
    return id;
  }
  @Override public boolean verifyContact(UUID userId, UUID contactId, String code) {
    return dsl.execute("UPDATE identity_contact SET verified_at=?,updated_at=? WHERE contact_id=? AND user_id=? AND removed_at IS NULL", Instant.now(), Instant.now(), contactId, userId) == 1;
  }
  @Override public boolean setPrimary(UUID userId, UUID contactId) {
    dsl.execute("UPDATE identity_contact SET primary_active=FALSE,updated_at=? WHERE user_id=?", Instant.now(), userId);
    return dsl.execute("UPDATE identity_contact SET primary_active=TRUE,updated_at=? WHERE contact_id=? AND user_id=? AND removed_at IS NULL", Instant.now(), contactId, userId) == 1;
  }
  @Override public boolean remove(UUID userId, UUID contactId) {
    return dsl.execute("UPDATE identity_contact SET removed_at=?,updated_at=? WHERE contact_id=? AND user_id=? AND removed_at IS NULL", Instant.now(), Instant.now(), contactId, userId) == 1;
  }
}
