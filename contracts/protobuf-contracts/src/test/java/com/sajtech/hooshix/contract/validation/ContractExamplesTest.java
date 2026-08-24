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
import com.sajtech.identity.contract.v1.CreateTenantRequest;
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
