package com.sajtech.notification.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SmtpEmailProviderConfigurationTest {
  @Test
  void googleProfilePinsEndpointSenderAndNormalizesGroupedAppPassword() {
    var configuration =
        SmtpEmailProviderConfiguration.googleGmail(
            "sender@gmail.com", "abcd efgh ijkl mnop", "Hooshix");

    assertThat(configuration.provider()).isEqualTo(EmailProviderKind.GOOGLE_GMAIL);
    assertThat(configuration.host()).isEqualTo("smtp.gmail.com");
    assertThat(configuration.port()).isEqualTo(587);
    assertThat(configuration.fromAddress()).isEqualTo("sender@gmail.com");
    assertThat(configuration.password()).isEqualTo("abcdefghijklmnop");
  }

  @Test
  void googleProfileRejectsEndpointOrSenderSubstitution() {
    assertThatThrownBy(
            () ->
                new SmtpEmailProviderConfiguration(
                    EmailProviderKind.GOOGLE_GMAIL,
                    "smtp.attacker.invalid",
                    587,
                    "sender@gmail.com",
                    "abcdefghijklmnop",
                    "sender@gmail.com",
                    "Hooshix"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SmtpEmailProviderConfiguration(
                    EmailProviderKind.GOOGLE_GMAIL,
                    "smtp.gmail.com",
                    587,
                    "sender@gmail.com",
                    "abcdefghijklmnop",
                    "other@gmail.com",
                    "Hooshix"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
