package com.sajtech.webbff.infrastructure.client;

import com.google.protobuf.ByteString;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.IdentityGateway.*;
import io.grpc.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class IdentityRegistrationGrpcClient extends IdentityGrpcClientSupport {
  IdentityRegistrationGrpcClient(ManagedChannel channel) {
    super(channel);
  }

  public RegisterResult register(
      UUID requestId,
      String channelName,
      String contact,
      String password,
      String localeName,
      String firstName,
      String lastName,
      String fatherName,
      byte[] clientAddress) {
    try {
      RegisterLocalRequest.Builder request =
          RegisterLocalRequest.newBuilder()
              .setRequestId(requestId.toString())
              .setChannel(registrationChannel(channelName))
              .setContact(contact)
              .setPassword(password)
              .setLocale(registrationLocale(localeName))
              .setFirstName(firstName)
              .setLastName(lastName)
              .setClientAddress(
                  TrustedClientAddress.newBuilder()
                      .setAddress(ByteString.copyFrom(clientAddress))
                      .build());
      if (fatherName != null && !fatherName.isBlank()) request.setFatherName(fatherName);
      var response = registrationStub().registerLocal(request.build());
      return new RegisterResult(response.getAccepted());
    } catch (StatusRuntimeException e) {
      throw mapRegistration(e);
    }
  }

  public boolean resendRegistration(
      UUID requestId, String channelName, String contact, byte[] clientAddress) {
    try {
      return registrationStub()
          .resendRegistrationVerification(
              ResendRegistrationVerificationRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setChannel(registrationChannel(channelName))
                  .setContact(contact)
                  .setClientAddress(
                      TrustedClientAddress.newBuilder()
                          .setAddress(ByteString.copyFrom(clientAddress))
                          .build())
                  .build())
          .getAccepted();
    } catch (StatusRuntimeException e) {
      throw mapRegistration(e);
    }
  }

  public boolean confirmRegistration(
      UUID requestId, String channelName, String contact, String code, byte[] clientAddress) {
    try {
      return registrationStub()
          .confirmRegistration(
              ConfirmRegistrationRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setChannel(registrationChannel(channelName))
                  .setContact(contact)
                  .setCode(code)
                  .setClientAddress(
                      TrustedClientAddress.newBuilder()
                          .setAddress(ByteString.copyFrom(clientAddress))
                          .build())
                  .build())
          .getConfirmed();
    } catch (StatusRuntimeException e) {
      throw mapRegistration(e);
    }
  }

  private IdentityRegistrationServiceGrpc.IdentityRegistrationServiceBlockingStub
      registrationStub() {
    return IdentityRegistrationServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  private static com.sajtech.identity.contract.v1.RegistrationChannel registrationChannel(
      String value) {
    return switch (value) {
      case "EMAIL" ->
          com.sajtech.identity.contract.v1.RegistrationChannel.REGISTRATION_CHANNEL_EMAIL;
      case "PHONE" ->
          com.sajtech.identity.contract.v1.RegistrationChannel.REGISTRATION_CHANNEL_PHONE;
      default ->
          throw new BffException(BffError.INVALID_REQUEST, "Registration channel is invalid");
    };
  }

  private static com.sajtech.identity.contract.v1.RegistrationLocale registrationLocale(
      String value) {
    return switch (value) {
      case "fa" -> com.sajtech.identity.contract.v1.RegistrationLocale.REGISTRATION_LOCALE_FA;
      case "en" -> com.sajtech.identity.contract.v1.RegistrationLocale.REGISTRATION_LOCALE_EN;
      default -> throw new BffException(BffError.INVALID_REQUEST, "Registration locale is invalid");
    };
  }
}
