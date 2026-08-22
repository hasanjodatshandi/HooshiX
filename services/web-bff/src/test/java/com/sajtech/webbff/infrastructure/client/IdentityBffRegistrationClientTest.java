package com.sajtech.webbff.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.identity.contract.v1.*;
import com.sajtech.webbff.application.BffError;
import com.sajtech.webbff.application.BffException;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IdentityBffRegistrationClientTest {
  @Test
  void mapsRegisterResendAndConfirmOverIdentityRegistrationGrpc() throws Exception {
    AtomicReference<RegisterLocalRequest> registered = new AtomicReference<>();
    AtomicReference<ResendRegistrationVerificationRequest> resent = new AtomicReference<>();
    AtomicReference<ConfirmRegistrationRequest> confirmed = new AtomicReference<>();
    String name = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                new IdentityRegistrationServiceGrpc.IdentityRegistrationServiceImplBase() {
                  @Override
                  public void registerLocal(
                      RegisterLocalRequest request,
                      StreamObserver<RegisterLocalResponse> observer) {
                    registered.set(request);
                    observer.onNext(RegisterLocalResponse.newBuilder().setAccepted(true).build());
                    observer.onCompleted();
                  }

                  @Override
                  public void resendRegistrationVerification(
                      ResendRegistrationVerificationRequest request,
                      StreamObserver<ResendRegistrationVerificationResponse> observer) {
                    resent.set(request);
                    observer.onNext(
                        ResendRegistrationVerificationResponse.newBuilder()
                            .setAccepted(true)
                            .build());
                    observer.onCompleted();
                  }

                  @Override
                  public void confirmRegistration(
                      ConfirmRegistrationRequest request,
                      StreamObserver<ConfirmRegistrationResponse> observer) {
                    confirmed.set(request);
                    observer.onNext(
                        ConfirmRegistrationResponse.newBuilder().setConfirmed(true).build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    try {
      IdentityBffClient client = new IdentityBffClient(channel);
      byte[] address = {(byte) 192, 0, 2, 7};
      UUID registerId = UUID.randomUUID();
      assertThat(
              client
                  .register(
                      registerId,
                      "EMAIL",
                      "person@example.com",
                      "password",
                      "en",
                      "First",
                      "Last",
                      "Father",
                      address)
                  .accepted())
          .isTrue();
      assertThat(
              client.resendRegistration(UUID.randomUUID(), "EMAIL", "person@example.com", address))
          .isTrue();
      assertThat(
              client.confirmRegistration(
                  UUID.randomUUID(), "EMAIL", "person@example.com", "12345678", address))
          .isTrue();

      assertThat(registered.get().getRequestId()).isEqualTo(registerId.toString());
      assertThat(registered.get().getChannel())
          .isEqualTo(RegistrationChannel.REGISTRATION_CHANNEL_EMAIL);
      assertThat(registered.get().getLocale()).isEqualTo(RegistrationLocale.REGISTRATION_LOCALE_EN);
      assertThat(registered.get().getFatherName()).isEqualTo("Father");
      assertThat(registered.get().getClientAddress().getAddress().toByteArray())
          .containsExactly(address);
      assertThat(resent.get().getContact()).isEqualTo("person@example.com");
      assertThat(confirmed.get().getCode()).isEqualTo("12345678");
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  @Test
  void semanticQuotaAndGlobalAdmissionResourceExhaustionRemainDistinct() throws Exception {
    assertThat(registrationFailure(Status.RESOURCE_EXHAUSTED.withDescription("QUOTA_EXCEEDED")))
        .isEqualTo(BffError.RATE_LIMITED);
    assertThat(
            registrationFailure(Status.RESOURCE_EXHAUSTED.withDescription("IDENTITY_UNAVAILABLE")))
        .isEqualTo(BffError.DEPENDENCY_UNAVAILABLE);
  }

  private static BffError registrationFailure(Status status) throws Exception {
    String name = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                new IdentityRegistrationServiceGrpc.IdentityRegistrationServiceImplBase() {
                  @Override
                  public void registerLocal(
                      RegisterLocalRequest request,
                      StreamObserver<RegisterLocalResponse> observer) {
                    observer.onError(status.asRuntimeException());
                  }
                })
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    try {
      IdentityBffClient client = new IdentityBffClient(channel);
      try {
        client.register(
            UUID.randomUUID(),
            "EMAIL",
            "person@example.com",
            "password",
            "en",
            "First",
            "Last",
            null,
            new byte[] {(byte) 192, 0, 2, 9});
        throw new AssertionError("Expected registration failure");
      } catch (BffException failure) {
        return failure.error();
      }
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  @Test
  void registrationFailureMappingIsStableAndNonEnumerating() throws Exception {
    String name = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                new IdentityRegistrationServiceGrpc.IdentityRegistrationServiceImplBase() {
                  @Override
                  public void registerLocal(
                      RegisterLocalRequest request,
                      StreamObserver<RegisterLocalResponse> observer) {
                    observer.onError(
                        Status.FAILED_PRECONDITION
                            .withDescription("COMPROMISED_PASSWORD")
                            .asRuntimeException());
                  }
                })
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    try {
      IdentityBffClient client = new IdentityBffClient(channel);
      assertThatThrownBy(
              () ->
                  client.register(
                      UUID.randomUUID(),
                      "EMAIL",
                      "person@example.com",
                      "password",
                      "en",
                      "First",
                      "Last",
                      null,
                      new byte[] {(byte) 192, 0, 2, 8}))
          .isInstanceOfSatisfying(
              BffException.class,
              failure -> assertThat(failure.error()).isEqualTo(BffError.REGISTRATION_REJECTED))
          .hasMessage("Registration request was rejected");
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }
}
