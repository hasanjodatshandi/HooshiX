package com.sajtech.identity.application.profile.port.in;

import com.sajtech.identity.application.profile.port.out.ProfileContactStore;
import java.util.List;
import java.util.UUID;

public interface ProfileManagement {
  ProfileContactStore.ProfileRecord profile(String refreshCredential);

  void update(
      String refreshCredential,
      UUID requestId,
      String firstName,
      String lastName,
      String fatherName);

  List<ProfileContactStore.ContactRecord> contacts(String refreshCredential);

  UUID addContact(
      String refreshCredential, UUID requestId, String type, String value, String locale);

  boolean resendContactVerification(String refreshCredential, UUID requestId, UUID contactId);

  boolean verifyContact(String refreshCredential, UUID requestId, UUID contactId, String code);

  boolean primary(String refreshCredential, UUID requestId, UUID contactId);

  boolean remove(String refreshCredential, UUID requestId, UUID contactId);
}
