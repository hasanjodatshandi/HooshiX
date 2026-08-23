package com.sajtech.identity.application.profile.port.out;

import java.util.List;
import java.util.UUID;

public interface ProfileContactStore {
  ProfileRecord findProfile(UUID userId);

  void updateProfile(UUID userId, String firstName, String lastName, String fatherName);

  List<ContactRecord> findContacts(UUID userId);

  UUID addContact(UUID userId, String type, String value);

  boolean verifyContact(UUID userId, UUID contactId, String code);

  boolean setPrimary(UUID userId, UUID contactId);

  boolean remove(UUID userId, UUID contactId);

  record ProfileRecord(UUID userId, String firstName, String lastName, String fatherName) {}

  record ContactRecord(UUID id, String type, String value, boolean verified, boolean primary) {}
}
