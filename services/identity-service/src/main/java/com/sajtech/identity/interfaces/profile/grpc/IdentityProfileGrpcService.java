package com.sajtech.identity.interfaces.profile.grpc;

import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.profile.port.in.ProfileManagement;
import com.sajtech.identity.contract.v1.*;
import io.grpc.stub.StreamObserver;
import java.util.UUID;

public final class IdentityProfileGrpcService
    extends IdentityProfileServiceGrpc.IdentityProfileServiceImplBase {
  private final ProfileManagement service;
  private final RefreshCredentialLookup lookup;
  private final AuthenticationStore authStore;

  public IdentityProfileGrpcService(
      ProfileManagement service, RefreshCredentialLookup lookup, AuthenticationStore authStore) {
    this.service = service;
    this.lookup = lookup;
    this.authStore = authStore;
  }

  private UUID user(String credential) {
    return lookup
        .lock(authStore, credential)
        .orElseThrow(() -> new IllegalArgumentException("invalid session"))
        .userId();
  }

  @Override
  public void getProfile(GetProfileRequest r, StreamObserver<GetProfileResponse> o) {
    var p = service.profile(user(r.getRefreshCredential()));
    o.onNext(
        GetProfileResponse.newBuilder()
            .setProfileId(p.userId().toString())
            .setFirstName(p.firstName())
            .setLastName(p.lastName())
            .setFatherName(p.fatherName())
            .build());
    o.onCompleted();
  }

  @Override
  public void updateProfile(UpdateProfileRequest r, StreamObserver<UpdateProfileResponse> o) {
    service.update(
        user(r.getRefreshCredential()), r.getFirstName(), r.getLastName(), r.getFatherName());
    o.onNext(UpdateProfileResponse.newBuilder().setUpdated(true).build());
    o.onCompleted();
  }

  @Override
  public void listContacts(ListContactsRequest r, StreamObserver<ListContactsResponse> o) {
    var b = ListContactsResponse.newBuilder();
    for (var c : service.contacts(user(r.getRefreshCredential())))
      b.addContacts(
          Contact.newBuilder()
              .setContactId(c.id().toString())
              .setType(c.type())
              .setValue(c.value())
              .setVerified(c.verified())
              .setPrimary(c.primary())
              .build());
    o.onNext(b.build());
    o.onCompleted();
  }

  @Override
  public void addContact(AddContactRequest r, StreamObserver<AddContactResponse> o) {
    var id = service.addContact(user(r.getRefreshCredential()), r.getType(), r.getValue());
    o.onNext(AddContactResponse.newBuilder().setContactId(id.toString()).setAccepted(true).build());
    o.onCompleted();
  }

  @Override
  public void verifyContact(VerifyContactRequest r, StreamObserver<VerifyContactResponse> o) {
    o.onNext(
        VerifyContactResponse.newBuilder()
            .setVerified(
                service.verifyContact(
                    user(r.getRefreshCredential()), UUID.fromString(r.getContactId()), r.getCode()))
            .build());
    o.onCompleted();
  }

  @Override
  public void setPrimaryContact(
      SetPrimaryContactRequest r, StreamObserver<SetPrimaryContactResponse> o) {
    o.onNext(
        SetPrimaryContactResponse.newBuilder()
            .setAccepted(
                service.primary(user(r.getRefreshCredential()), UUID.fromString(r.getContactId())))
            .build());
    o.onCompleted();
  }

  @Override
  public void removeContact(RemoveContactRequest r, StreamObserver<RemoveContactResponse> o) {
    o.onNext(
        RemoveContactResponse.newBuilder()
            .setAccepted(
                service.remove(user(r.getRefreshCredential()), UUID.fromString(r.getContactId())))
            .build());
    o.onCompleted();
  }
}
