package com.sajtech.hooshix.contract.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import build.buf.protovalidate.ValidatorFactory;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.sajtech.authorization.contract.v1.CheckPermissionRequest;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixRequest;
import com.sajtech.identity.contract.v1.AddContactRequest;
import com.sajtech.identity.contract.v1.AuthenticateLocalRequest;
import com.sajtech.identity.contract.v1.AuthenticateLocalResponse;
import com.sajtech.identity.contract.v1.AuthenticationSessionMode;
import com.sajtech.identity.contract.v1.CreateTenantRequest;
import com.sajtech.identity.contract.v1.CompleteMfaAuthenticationRequest;
import com.sajtech.identity.contract.v1.MfaProof;
import com.sajtech.identity.contract.v1.MfaProofType;
import com.sajtech.identity.contract.v1.MfaSessionCredentials;
import com.sajtech.identity.contract.v1.RegisterLocalRequest;
import com.sajtech.identity.contract.v1.ReportNotificationResultRequest;
import com.sajtech.identity.contract.v1.RequestPasswordRecoveryRequest;
import com.sajtech.notification.contract.v1.SubmitNotificationRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class ContractExamplesTest {
  private static final Path EXAMPLES = Path.of("examples");

  @TestFactory
  Stream<DynamicTest> documentedValidExamplesConformToTheirSchemas() {
    return Stream.of(
            example("authorization/v1/check-permission.valid.json", CheckPermissionRequest.newBuilder()),
            example("compromisedpassword/v1/lookup-prefix.valid.json", LookupPrefixRequest.newBuilder()),
            example("identity/v1/authenticate-local.valid.json", AuthenticateLocalRequest.newBuilder()),
            example(
                "identity/v1/complete-mfa-authentication.valid.json",
                CompleteMfaAuthenticationRequest.newBuilder()),
            example(
                "identity/v1/report-notification-result.valid.json",
                ReportNotificationResultRequest.newBuilder()),
            example(
                "identity/v1/request-password-recovery.valid.json",
                RequestPasswordRecoveryRequest.newBuilder()),
            example("identity/v1/add-contact.valid.json", AddContactRequest.newBuilder()),
            example("identity/v1/register-local.valid.json", RegisterLocalRequest.newBuilder()),
            example("identity/v1/create-tenant.valid.json", CreateTenantRequest.newBuilder()),
            example("notification/v1/submit-notification.valid.json", SubmitNotificationRequest.newBuilder()))
        .map(
            example ->
                DynamicTest.dynamicTest(
                    example.path(),
                    () -> {
                      Message message = parse(example);
                      assertTrue(
                          ValidatorFactory.newBuilder().build().validate(message).isSuccess(),
                          "documented example must satisfy contract validation");
                    }));
  }

  @Test
  void documentedInvalidExampleIsRejected() throws Exception {
    Message message =
        parse(
            example(
                "compromisedpassword/v1/lookup-prefix.invalid.json",
                LookupPrefixRequest.newBuilder()));

    assertFalse(ValidatorFactory.newBuilder().build().validate(message).isSuccess());
  }

  @Test
  void mfaProofTypeAndCodeShapeMustAgree() throws Exception {
    var validator = ValidatorFactory.newBuilder().build();

    assertTrue(
        validator
            .validate(
                MfaProof.newBuilder()
                    .setType(MfaProofType.MFA_PROOF_TYPE_TOTP)
                    .setCode("123456")
                    .build())
            .isSuccess());
    assertFalse(
        validator
            .validate(
                MfaProof.newBuilder()
                    .setType(MfaProofType.MFA_PROOF_TYPE_TOTP)
                    .setCode("AAAA-BBBB-CCCC-DDDD")
                    .build())
            .isSuccess());
  }

  @Test
  void mfaRequiredAuthenticationResponseCannotCarrySessionCredentials() throws Exception {
    var validator = ValidatorFactory.newBuilder().build();
    var valid =
        AuthenticateLocalResponse.newBuilder()
            .setSessionMode(AuthenticationSessionMode.AUTHENTICATION_SESSION_MODE_MFA_REQUIRED)
            .setUserId("11111111-1111-4111-8111-111111111111")
            .setMfaChallenge("C".repeat(43))
            .build();

    assertTrue(validator.validate(valid).isSuccess());
    assertFalse(
        validator.validate(valid.toBuilder().setRefreshCredential("R".repeat(43)).build()).isSuccess());
    assertFalse(
        validator
            .validate(
                valid.toBuilder()
                    .setSelectedTenantId("22222222-2222-4222-8222-222222222222")
                    .build())
            .isSuccess());
  }

  @Test
  void completedMfaSessionRequiresTenantContextMatchingItsMode() throws Exception {
    var validator = ValidatorFactory.newBuilder().build();
    var onboarding =
        MfaSessionCredentials.newBuilder()
            .setIdentitySessionId("S".repeat(43))
            .setRefreshFamilyId("11111111-1111-4111-8111-111111111111")
            .setRefreshCredential("R".repeat(43))
            .setRefreshIdleExpiresAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(1))
            .setRefreshAbsoluteExpiresAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(2))
            .setSessionMode(
                AuthenticationSessionMode.AUTHENTICATION_SESSION_MODE_AUTHENTICATED_ONBOARDING)
            .setUserId("22222222-2222-4222-8222-222222222222")
            .build();

    assertTrue(validator.validate(onboarding).isSuccess());
    assertFalse(
        validator
            .validate(
                onboarding.toBuilder()
                    .setSessionMode(
                        AuthenticationSessionMode.AUTHENTICATION_SESSION_MODE_TENANT_AUTHENTICATED)
                    .build())
            .isSuccess());
  }

  private static Example example(String path, Message.Builder builder) {
    return new Example(path, builder);
  }

  private static Message parse(Example example) throws IOException {
    try (var reader = Files.newBufferedReader(EXAMPLES.resolve(example.path()))) {
      JsonFormat.parser().merge(reader, example.builder());
      return example.builder().build();
    }
  }

  private record Example(String path, Message.Builder builder) {}
}
