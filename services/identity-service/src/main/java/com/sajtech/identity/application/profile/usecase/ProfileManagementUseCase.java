package com.sajtech.identity.application.profile.usecase;

import com.sajtech.identity.application.profile.port.in.ProfileManagement;
import com.sajtech.identity.application.profile.port.out.ProfileContactStore;
import java.util.List;
import java.util.UUID;

public final class ProfileManagementUseCase implements ProfileManagement {
  private final ProfileContactStore store;

  public ProfileManagementUseCase(ProfileContactStore store) {
    this.store = store;
  }

  public ProfileContactStore.ProfileRecord profile(UUID userId) {
    return store.findProfile(userId);
  }

  public void update(UUID userId, String firstName, String lastName, String fatherName) {
    store.updateProfile(userId, firstName, lastName, fatherName);
  }

  public List<ProfileContactStore.ContactRecord> contacts(UUID userId) {
    return store.findContacts(userId);
  }

  public UUID addContact(UUID userId, String type, String value) {
    return store.addContact(userId, type, value);
  }

  public boolean verifyContact(UUID userId, UUID contactId, String code) {
    return store.verifyContact(userId, contactId, code);
  }

  public boolean primary(UUID userId, UUID contactId) {
    return store.setPrimary(userId, contactId);
  }

  public boolean remove(UUID userId, UUID contactId) {
    return store.remove(userId, contactId);
  }
}
