package com.sajtech.notification.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedNotificationProviderConfigurationTest {
  @TempDir Path temporary;

  @Test
  void loadsGoogleAndSmsIrConfigurationWithoutProviderEndpointSubstitution() throws Exception {
    Path path = temporary.resolve("providers.properties");
    Files.writeString(path, googleConfiguration());

    NotificationProviderConfiguration configuration =
        FileBackedNotificationProviderConfiguration.load(path);

    assertThat(configuration.email().provider()).isEqualTo(EmailProviderKind.GOOGLE_GMAIL);
    assertThat(configuration.email().host()).isEqualTo("smtp.gmail.com");
    assertThat(configuration.email().fromAddress()).isEqualTo("sender@gmail.com");
    assertThat(configuration.sms().baseUri().toString()).isEqualTo("https://api.sms.ir");
  }

  @Test
  void rejectsLegacyUnknownAndDuplicateKeys() throws Exception {
    Path legacy = temporary.resolve("legacy.properties");
    Files.writeString(legacy, googleConfiguration() + "liara.smtp.host=smtp.fixture.invalid\n");
    assertThatThrownBy(() -> FileBackedNotificationProviderConfiguration.load(legacy))
        .isInstanceOf(IllegalStateException.class);

    Path duplicate = temporary.resolve("duplicate.properties");
    Files.writeString(duplicate, googleConfiguration() + "email.from-name=Duplicate\n");
    assertThatThrownBy(() -> FileBackedNotificationProviderConfiguration.load(duplicate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Notification provider configuration is invalid")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void genericSmtpRequiresExplicitEndpointAndSender() throws Exception {
    Path path = temporary.resolve("generic.properties");
    Files.writeString(
        path,
        """
        email.provider=GENERIC_SMTP
        email.smtp.host=smtp.fixture.invalid
        email.smtp.port=587
        email.smtp.username=fixture-user
        email.smtp.password=fixture-value
        email.from-address=no-reply@fixture.invalid
        email.from-name=Hooshix
        sms.provider=SMSIR
        smsir.api-key=fixture-token-value
        smsir.line-number=30004505000017
        """);

    NotificationProviderConfiguration configuration =
        FileBackedNotificationProviderConfiguration.load(path);
    assertThat(configuration.email().provider()).isEqualTo(EmailProviderKind.GENERIC_SMTP);
    assertThat(configuration.email().host()).isEqualTo("smtp.fixture.invalid");
  }

  private static String googleConfiguration() {
    return """
        email.provider=GOOGLE_GMAIL
        email.smtp.username=sender@gmail.com
        email.smtp.password=abcd efgh ijkl mnop
        email.from-name=Hooshix
        sms.provider=SMSIR
        smsir.api-key=fixture-token-value
        smsir.line-number=30004505000017
        """;
  }
}
