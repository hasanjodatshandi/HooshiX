package com.sajtech.notification.application.submit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import org.junit.jupiter.api.Test;

class NotificationRecipientCanonicalizerTest {
  private final NotificationRecipientCanonicalizer canonicalizer =
      new NotificationRecipientCanonicalizer();

  @Test
  void lowercasesOnlyEmailDomain() {
    assertThat(canonicalizer.canonicalize(NotificationChannel.EMAIL, "User@Example.COM"))
        .isEqualTo("User@example.com");
  }

  @Test
  void normalizesPresentationSeparatorsInE164Phone() {
    assertThat(canonicalizer.canonicalize(NotificationChannel.SMS, "+98 (912) 345-6789"))
        .isEqualTo("+989123456789");
  }

  @Test
  void rejectsNonCanonicalPhonePrefix() {
    assertThatThrownBy(() -> canonicalizer.canonicalize(NotificationChannel.SMS, "00989123456789"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
