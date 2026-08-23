package com.sajtech.identity.application.profile.port.in;

import com.sajtech.identity.application.profile.port.out.ProfileContactStore;
import java.util.List;
import java.util.UUID;

public interface ProfileManagement {
  ProfileContactStore.ProfileRecord profile(UUID userId);

  void update(UUID userId, String firstName, String lastName, String fatherName);

  List<ProfileContactStore.ContactRecord> contacts(UUID userId);

  UUID addContact(UUID userId, String type, String value);

  boolean verifyContact(UUID userId, UUID contactId, String code);

  boolean primary(UUID userId, UUID contactId);

  boolean remove(UUID userId, UUID contactId);
}
