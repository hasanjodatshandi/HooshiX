package com.sajtech.identity.interfaces.profile.grpc;

import com.sajtech.identity.application.profile.*;
import com.sajtech.identity.application.profile.port.in.ProfileManagement;
import com.sajtech.identity.contract.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;

public final class IdentityProfileGrpcService
    extends IdentityProfileServiceGrpc.IdentityProfileServiceImplBase {
  private final ProfileManagement service;

  public IdentityProfileGrpcService(ProfileManagement service) {
    this.service = service;
  }

  @Override
  public void getProfile(GetProfileRequest request, StreamObserver<GetProfileResponse> observer) {
    try {
      var profile = service.profile(request.getRefreshCredential());
      observer.onNext(
          GetProfileResponse.newBuilder()
              .setProfileId(profile.userId().toString())
              .setFirstName(profile.firstName())
              .setLastName(profile.lastName())
              .setFatherName(profile.fatherName() == null ? "" : profile.fatherName())
              .build());
      observer.onCompleted();
    } catch (ProfileException exception) {
      observer.onError(status(exception).asRuntimeException());
    }
  }

  @Override
  public void updateProfile(
      UpdateProfileRequest request, StreamObserver<UpdateProfileResponse> observer) {
    try {
      service.update(
          request.getRefreshCredential(),
          requestId(request.getRequestId()),
          request.getFirstName(),
          request.getLastName(),
          request.getFatherName());
      observer.onNext(UpdateProfileResponse.newBuilder().setUpdated(true).build());
      observer.onCompleted();
    } catch (ProfileException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(invalid());
    }
  }

  @Override
  public void listContacts(
      ListContactsRequest request, StreamObserver<ListContactsResponse> observer) {
    try {
      var response = ListContactsResponse.newBuilder();
      for (var contact : service.contacts(request.getRefreshCredential())) {
        response.addContacts(
            Contact.newBuilder()
                .setContactId(contact.id().toString())
                .setType(contact.type())
                .setValue(contact.value())
                .setVerified(contact.verified())
                .setPrimary(contact.primary())
                .build());
      }
      observer.onNext(response.build());
      observer.onCompleted();
    } catch (ProfileException exception) {
      observer.onError(status(exception).asRuntimeException());
    }
  }

  @Override
  public void addContact(AddContactRequest request, StreamObserver<AddContactResponse> observer) {
    try {
      UUID contactId =
          service.addContact(
              request.getRefreshCredential(),
              requestId(request.getRequestId()),
              request.getType(),
              request.getValue(),
              request.getLocale());
      observer.onNext(
          AddContactResponse.newBuilder()
              .setContactId(contactId.toString())
              .setAccepted(true)
              .build());
      observer.onCompleted();
    } catch (ProfileException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(invalid());
    }
  }

  @Override
  public void resendContactVerification(
      ResendContactVerificationRequest request,
      StreamObserver<ResendContactVerificationResponse> observer) {
    try {
      boolean accepted =
          service.resendContactVerification(
              request.getRefreshCredential(),
              requestId(request.getRequestId()),
              id(request.getContactId()));
      observer.onNext(ResendContactVerificationResponse.newBuilder().setAccepted(accepted).build());
      observer.onCompleted();
    } catch (ProfileException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(invalid());
    }
  }

  @Override
  public void verifyContact(
      VerifyContactRequest request, StreamObserver<VerifyContactResponse> observer) {
    try {
      boolean verified =
          service.verifyContact(
              request.getRefreshCredential(),
              requestId(request.getRequestId()),
              id(request.getContactId()),
              request.getCode());
      observer.onNext(VerifyContactResponse.newBuilder().setVerified(verified).build());
      observer.onCompleted();
    } catch (ProfileException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(invalid());
    }
  }

  @Override
  public void setPrimaryContact(
      SetPrimaryContactRequest request, StreamObserver<SetPrimaryContactResponse> observer) {
    try {
      boolean accepted =
          service.primary(
              request.getRefreshCredential(),
              requestId(request.getRequestId()),
              id(request.getContactId()));
      observer.onNext(SetPrimaryContactResponse.newBuilder().setAccepted(accepted).build());
      observer.onCompleted();
    } catch (ProfileException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(invalid());
    }
  }

  @Override
  public void removeContact(
      RemoveContactRequest request, StreamObserver<RemoveContactResponse> observer) {
    try {
      boolean accepted =
          service.remove(
              request.getRefreshCredential(),
              requestId(request.getRequestId()),
              id(request.getContactId()));
      observer.onNext(RemoveContactResponse.newBuilder().setAccepted(accepted).build());
      observer.onCompleted();
    } catch (ProfileException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(invalid());
    }
  }

  private static UUID requestId(String value) {
    if (value == null
        || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("Request ID is invalid");
    }
    return UUID.fromString(value);
  }

  private static UUID id(String value) {
    UUID result = UUID.fromString(value);
    if (result.version() != 4 || !result.toString().equals(value)) {
      throw new IllegalArgumentException("ID is invalid");
    }
    return result;
  }

  private static RuntimeException invalid() {
    return Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException();
  }

  private static Status status(ProfileException exception) {
    Status base =
        switch (exception.error()) {
          case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
          case INVALID_SESSION -> Status.UNAUTHENTICATED;
          case RECENT_AUTHENTICATION_REQUIRED,
              CONTACT_NOT_VERIFIED,
              PRIMARY_CONTACT_REQUIRED,
              RESEND_TOO_SOON ->
              Status.FAILED_PRECONDITION;
          case CONTACT_CONFLICT, REQUEST_ID_CONFLICT -> Status.ALREADY_EXISTS;
          case CONTACT_LIMIT_REACHED -> Status.RESOURCE_EXHAUSTED;
          case INVALID_VERIFICATION_CODE, VERIFICATION_EXHAUSTED -> Status.PERMISSION_DENIED;
          case NOT_FOUND -> Status.NOT_FOUND;
        };
    return base.withDescription(exception.error().name());
  }
}
