package com.sajtech.webbff.infrastructure.client;

import com.sajtech.identity.contract.v1.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.IdentityGateway.*;
import com.sajtech.webbff.application.port.out.IdentityGateway.Contact;
import io.grpc.*;
import java.util.*;
import java.util.function.Supplier;

final class IdentityProfileGrpcClient extends IdentityGrpcClientSupport {
  IdentityProfileGrpcClient(ManagedChannel channel) {
    super(channel);
  }

  public Profile profile(String refresh) {
    return profileCall(
        () -> {
          var r =
              profileStub()
                  .getProfile(GetProfileRequest.newBuilder().setRefreshCredential(refresh).build());
          return new Profile(
              uuid(r.getProfileId()), r.getFirstName(), r.getLastName(), r.getFatherName());
        });
  }

  public List<Contact> contacts(String refresh) {
    return profileCall(
        () ->
            profileStub()
                .listContacts(
                    ListContactsRequest.newBuilder().setRefreshCredential(refresh).build())
                .getContactsList()
                .stream()
                .map(
                    c ->
                        new Contact(
                            uuid(c.getContactId()),
                            c.getType(),
                            c.getValue(),
                            c.getVerified(),
                            c.getPrimary()))
                .toList());
  }

  public boolean updateProfile(
      UUID requestId, String refresh, String firstName, String lastName, String fatherName) {
    return profileCall(
        () ->
            profileStub()
                .updateProfile(
                    UpdateProfileRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setRequestId(requestId.toString())
                        .setFirstName(firstName)
                        .setLastName(lastName)
                        .setFatherName(fatherName == null ? "" : fatherName)
                        .build())
                .getUpdated());
  }

  public UUID addContact(UUID requestId, String refresh, String type, String value, String locale) {
    return profileCall(
        () ->
            uuid(
                profileStub()
                    .addContact(
                        AddContactRequest.newBuilder()
                            .setRefreshCredential(refresh)
                            .setType(type)
                            .setValue(value)
                            .setRequestId(requestId.toString())
                            .setLocale(locale)
                            .build())
                    .getContactId()));
  }

  public boolean resendContactVerification(UUID requestId, String refresh, UUID id) {
    return profileCall(
        () ->
            profileStub()
                .resendContactVerification(
                    ResendContactVerificationRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setContactId(id.toString())
                        .setRequestId(requestId.toString())
                        .build())
                .getAccepted());
  }

  public boolean verifyContact(UUID requestId, String refresh, UUID id, String code) {
    return profileCall(
        () ->
            profileStub()
                .verifyContact(
                    VerifyContactRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setContactId(id.toString())
                        .setCode(code)
                        .setRequestId(requestId.toString())
                        .build())
                .getVerified());
  }

  public boolean setPrimaryContact(UUID requestId, String refresh, UUID id) {
    return profileCall(
        () ->
            profileStub()
                .setPrimaryContact(
                    SetPrimaryContactRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setContactId(id.toString())
                        .setRequestId(requestId.toString())
                        .build())
                .getAccepted());
  }

  public boolean removeContact(UUID requestId, String refresh, UUID id) {
    return profileCall(
        () ->
            profileStub()
                .removeContact(
                    RemoveContactRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setContactId(id.toString())
                        .setRequestId(requestId.toString())
                        .build())
                .getAccepted());
  }

  private static <T> T profileCall(Supplier<T> call) {
    try {
      return call.get();
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }
}
